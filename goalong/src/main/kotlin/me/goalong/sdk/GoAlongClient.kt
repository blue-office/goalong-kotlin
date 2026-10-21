package me.goalong.sdk

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * SDK の本体。[GoAlong] は configure 済みのこのクラスに委譲する。
 * 通信・保存・Install Referrer・時計を差し替えられるので、JVM の単体テストで直接使う。
 */
internal class GoAlongClient(
    val appId: String,
    val appSlug: String?,
    baseUrl: String,
    private val store: KeyValueStore,
    private val transport: HttpTransport,
    private val device: DeviceInfo,
    private val referrerSource: InstallReferrerSource?,
    @Volatile var subscriberCheck: (suspend () -> Boolean)?,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val emit: (GoAlongEvent) -> Unit = {},
    private val sdkVersion: String = BuildConfig.SDK_VERSION,
    private val timeoutMs: Long = UrlConnectionTransport.TIMEOUT_MS.toLong(),
) {
    val baseUrl: String = baseUrl.trimEnd('/')
    val expectedHost: String = runCatching { URI(this.baseUrl).host?.lowercase() }.getOrNull()
        ?: ReferralLink.DEFAULT_HOST

    /** apply・claim・current・remove・Install Referrer の適用を、通信開始から保存完了まで直列にする */
    private val mutex = Mutex()
    private val entryMutex = Mutex()
    private val installationLock = Any()

    private data class EntryCache(val enabled: Boolean, val expiresAtMs: Long)

    @Volatile private var entryCache: EntryCache? = null
    @Volatile private var lastEntryEnabled: Boolean? = null

    private val entryShown = AtomicBoolean(false)
    private val referrerFetchStarted = AtomicBoolean(false)

    // MARK: - 起動

    /** installation の報告と Install Referrer の読み取りを裏で始める */
    fun start(): List<Job> = listOfNotNull(
        scope.launch { reportInstallationIfNeeded() },
        startInstallReferrerIfNeeded(),
    )

    // MARK: - ローカル状態

    /**
     * 端末ごとの installation ID。保存できなかったら [GoAlongException.Storage] を投げる
     * (保存できない ID をサーバーに送ると、再起動後に別の ID になり紹介が失われるため)
     */
    val installationId: String
        get() = synchronized(installationLock) {
            store.getString(StorageKeys.INSTALLATION_ID)
                ?: UUID.randomUUID().toString().lowercase().also { id ->
                    save { put(StorageKeys.INSTALLATION_ID, id) }
                }
        }

    val hasAppliedReferral: Boolean get() = store.getBoolean(StorageKeys.REFERRAL_APPLIED)

    val revenueCatUserId: String?
        get() = store.getString(StorageKeys.RC_APP_USER_ID)?.takeIf { it.isNotEmpty() }

    val referralOfferingKey: String? get() = store.getString(StorageKeys.REFERRAL_OFFERING_KEY)
    val referralPerkLabel: String? get() = store.getString(StorageKeys.REFERRAL_PERK_LABEL)

    /** 付与済み特典の端末キャッシュ(設定画面を開いた瞬間に出すため)。期限切れは返さない */
    val cachedAppliedPerk: GoAlongPerk?
        get() {
            val label = store.getString(StorageKeys.APPLIED_PERK_LABEL) ?: return null
            val expires = store.getLong(StorageKeys.APPLIED_PERK_EXPIRES_AT_MS)
            if (expires != null && expires <= now()) return null
            return GoAlongPerk(
                label = label,
                entitlement = store.getString(StorageKeys.APPLIED_PERK_ENTITLEMENT),
                expiresAtMs = expires,
            )
        }

    val installReferrerState: InstallReferrerState
        get() = InstallReferrerState.from(store.getString(StorageKeys.INSTALL_REFERRER_STATE))

    /**
     * RevenueCat の Subscriber Attributes に渡す属性。
     * 紹介が成立していれば `goalong_referred` と(あれば)`goalong_offering` を含む
     */
    fun attributionAttributes(): Map<String, String> {
        val attrs = linkedMapOf("goalong_installation_id" to installationId)
        if (hasAppliedReferral) {
            attrs["goalong_referred"] = "true"
            referralOfferingKey?.let { attrs["goalong_offering"] = it }
        }
        return attrs
    }

    fun extractCode(text: String): String? = ReferralLink.extract(text, expectedHost, appSlug)

    private fun normalizedCode(text: String): String? =
        ReferralLink.normalizedCode(text, expectedHost, appSlug)

    // MARK: - API

    suspend fun validateCode(raw: String): CodeValidation {
        val code = normalizedCode(raw)
            ?: return CodeValidation(false, CodeErrorKind.INVALID_FORMAT, null, null, null)
        val json = withContext(dispatcher) {
            request("codes/validate", JSONObject().put("appId", appId).put("code", code))
        }
        if (!json.has("valid")) throw GoAlongException.Network(IllegalStateException("missing valid"))
        return CodeValidation(
            valid = json.optBoolean("valid", false),
            error = CodeErrorKind.from(json.stringOrNull("error")),
            code = json.stringOrNull("code"),
            creator = parseCreator(json.optJSONObject("creator")),
            perk = parsePerk(json.optJSONObject("perk")),
        )
    }

    suspend fun applyCode(
        raw: String,
        method: ApplyMethod = ApplyMethod.CODE,
        rcAppUserId: String? = null,
        confirmReplace: Boolean = false,
    ): ApplyResult {
        if (normalizedCode(raw) == null) return ApplyResult.NotFound(CodeErrorKind.INVALID_FORMAT)
        // 購読判定はアプリのコードを呼ぶので、ロックの外で行う(中で GoAlong の API を待つとロックが再入できず止まる)
        if (isActiveSubscriber()) {
            GoAlongLog.log("applyCode: 既存購読者のため適用しない")
            return ApplyResult.ExistingSubscriber
        }
        return serial { applyLocked(raw, method, rcAppUserId, confirmReplace) }
    }

    private suspend fun applyLocked(
        raw: String,
        method: ApplyMethod,
        rcAppUserId: String?,
        confirmReplace: Boolean,
    ): ApplyResult {
        // 購読判定は呼び出し側がロックの外で済ませている
        val code = normalizedCode(raw) ?: return ApplyResult.NotFound(CodeErrorKind.INVALID_FORMAT)
        val body = JSONObject()
            .put("appId", appId)
            .put("installationId", installationId)
            .put("code", code)
            .put("method", method.value)
            .put("confirmReplace", confirmReplace)
        (rcAppUserId ?: revenueCatUserId)?.let { body.put("rcAppUserId", it) }
        putDeviceMeta(body)
        val json = request("codes/apply", body)
        return when (val status = json.stringOrNull("status")) {
            "applied", "already_applied" -> {
                val creator = parseCreator(json.optJSONObject("creator"))
                val perk = parsePerk(json.optJSONObject("perk"))
                val offeringKey = json.stringOrNull("offeringKey")
                val perkLabel = json.stringOrNull("perkLabel")
                // サーバーの応答を正として置き換える(already_applied でも古い値を残さない)
                save {
                    put(StorageKeys.REFERRAL_APPLIED, true)
                    put(StorageKeys.REFERRAL_OFFERING_KEY, offeringKey)
                    put(StorageKeys.REFERRAL_PERK_LABEL, perkLabel)
                    putCachedPerk(perk)
                }
                if (status == "applied") {
                    ApplyResult.Applied(creator, perk, offeringKey, perkLabel)
                } else {
                    ApplyResult.AlreadyApplied(creator, perk, offeringKey, perkLabel)
                }
            }
            "conflict" -> ApplyResult.Conflict(parseCreator(json.optJSONObject("currentCreator")))
            "existing_subscriber" -> ApplyResult.ExistingSubscriber
            "expired" -> ApplyResult.NotFound(CodeErrorKind.EXPIRED)
            "not_found" -> ApplyResult.NotFound(CodeErrorKind.from(json.stringOrNull("error")))
            else -> throw GoAlongException.Network(IllegalStateException("unknown apply status: $status"))
        }
    }

    /** RC の ID を保存し、保留中の Install Referrer を適用し、紹介済みなら特典を取りに行く */
    suspend fun setRevenueCatUserId(id: String): GoAlongPerk? {
        try {
            save { put(StorageKeys.RC_APP_USER_ID, id) }
        } catch (e: GoAlongException.Storage) {
            // 保存できなくても、この呼び出しでは渡された ID を使う
            GoAlongLog.log("RC の ID を保存できず: $e")
        }
        GoAlongLog.log("setRevenueCatUserId ${id.take(12)}…")
        // 保留中の Install Referrer を、この ID で適用する。適用時に特典が付与されたらそれを返し、
        // 追加の claim はしない(サーバーは二重に付与しないので、claim は perk なしで返り、保存した特典を消してしまう)
        when (val auto = tryApplyPendingReferrer(rcAppUserId = id)) {
            is ApplyResult.Applied -> auto.perk?.let { return it }
            is ApplyResult.AlreadyApplied -> auto.perk?.let { return it }
            else -> Unit
        }
        return try {
            serial { claimLocked(id) }
        } catch (e: GoAlongException) {
            // 取りに行けなくても実害はない(次回の呼び出しで再試行される)
            GoAlongLog.log("claim 失敗: $e")
            null
        }
    }

    private suspend fun claimLocked(rcAppUserId: String): GoAlongPerk? {
        if (!hasAppliedReferral) return null
        val json = request(
            "perks/claim",
            JSONObject()
                .put("appId", appId)
                .put("installationId", installationId)
                .put("rcAppUserId", rcAppUserId),
        )
        return when (val status = json.stringOrNull("status")) {
            "granted", "no_perk" -> {
                val perk = parsePerk(json.optJSONObject("perk"))
                save {
                    put(StorageKeys.REFERRAL_OFFERING_KEY, json.stringOrNull("offeringKey"))
                    put(StorageKeys.REFERRAL_PERK_LABEL, json.stringOrNull("perkLabel"))
                    // granted なのに perk が無いのは付与済み(二重に付与しない)の応答。保存済みの特典は消さない
                    if (status == "no_perk" || perk != null) putCachedPerk(perk)
                }
                if (status == "granted") {
                    perk?.let { emit(GoAlongEvent.PerkGranted(it)) }
                    // 付与済み(perk なしの granted)なら、保存済みの特典を返す
                    perk ?: cachedAppliedPerk
                } else {
                    null
                }
            }
            "no_attribution" -> {
                clearReferral()
                null
            }
            "existing_subscriber" -> null
            else -> throw GoAlongException.Network(IllegalStateException("unknown claim status: $status"))
        }
    }

    suspend fun currentReferral(): GoAlongReferralSnapshot = serial {
        val json = request(
            "codes/current",
            query = mapOf("appId" to appId, "installationId" to installationId),
        )
        when (val status = json.stringOrNull("status")) {
            "applied" -> {
                val perkJson = json.optJSONObject("perk")
                val perk = perkJson?.stringOrNull("label")?.let {
                    GoAlongPerk(label = it, expiresAtMs = perkJson.longOrNull("expiresAtMs"))
                }
                // サーバーが正。次回は通信を待たずに同じ内容を出せるようキャッシュを揃える
                save {
                    put(StorageKeys.REFERRAL_APPLIED, true)
                    putCachedPerk(perk)
                }
                GoAlongReferralSnapshot.Applied(
                    creator = parseCreator(json.optJSONObject("creator")),
                    appliedAtMs = parseIsoDate(json.stringOrNull("appliedAt")),
                    perk = perk,
                )
            }
            "removed" -> {
                clearReferral(suppressAutoApply = true)
                GoAlongReferralSnapshot.Removed(parseIsoDate(json.stringOrNull("removedAt")))
            }
            "none" -> {
                clearReferral()
                GoAlongReferralSnapshot.None
            }
            else -> throw GoAlongException.Network(IllegalStateException("unknown referral status: $status"))
        }
    }

    /** 紹介を取り消す。取り消した後は Install Referrer で再び自動適用しない */
    suspend fun removeReferral(): Boolean = serial {
        val json = request(
            "codes/remove",
            JSONObject().put("appId", appId).put("installationId", installationId),
        )
        val status = json.stringOrNull("status")
        if (status != "removed" && status != "no_attribution") {
            throw GoAlongException.Network(IllegalStateException("unknown removal status: $status"))
        }
        clearReferral(suppressAutoApply = true)
        GoAlongLog.log("removeReferral -> $status")
        status == "removed"
    }

    /**
     * 紹介コードの入口を出してよいか。サーバー指定の秒数だけメモリにキャッシュし、同時の取得はまとめる。
     * 失敗したときは直近の結果、無ければ false(失敗はキャッシュしない)
     */
    suspend fun referralEntryEnabled(): Boolean {
        freshEntry()?.let { return it }
        return entryMutex.withLock {
            freshEntry()?.let { return@withLock it }
            try {
                val json = withContext(dispatcher) { request("config", query = mapOf("appId" to appId)) }
                val enabled = json.getBoolean("entryEnabled")
                val maxAge = json.optLong("cacheMaxAgeSeconds", 0).coerceAtLeast(0)
                entryCache = EntryCache(enabled, now() + maxAge * 1000)
                lastEntryEnabled = enabled
                GoAlongLog.log("referralEntryEnabled = $enabled")
                enabled
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                GoAlongLog.log("referralEntryEnabled 取得失敗: $e")
                lastEntryEnabled ?: false
            }
        }
    }

    private fun freshEntry(): Boolean? = entryCache?.takeIf { now() < it.expiresAtMs }?.enabled

    /** アプリが渡した購読判定。未設定・失敗は false(サーバーでも rcAppUserId で再判定する) */
    suspend fun isActiveSubscriber(): Boolean {
        val check = subscriberCheck ?: return false
        return try {
            check()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GoAlongLog.log("isActiveSubscriber 失敗: $e")
            false
        }
    }

    // MARK: - Install Referrer(計画書 §2-3)

    fun startInstallReferrerIfNeeded(): Job? {
        if (referrerSource == null || installReferrerState != InstallReferrerState.UNREAD) return null
        if (!referrerFetchStarted.compareAndSet(false, true)) return null
        return scope.launch { readInstallReferrer() }
    }

    suspend fun readInstallReferrer() {
        try {
            readInstallReferrerOrThrow()
        } catch (e: GoAlongException) {
            // 保存に失敗したときは unread のまま(次回起動で再試行)
            GoAlongLog.log("Install Referrer の処理に失敗: $e")
        }
    }

    private suspend fun readInstallReferrerOrThrow() {
        val source = referrerSource ?: return
        val result = try {
            source.fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReferrerFetchResult.RetryLater
        }
        serial {
            // 取得中に取り消し(suppress)された場合などは書き込まない
            if (installReferrerState != InstallReferrerState.UNREAD) return@serial
            when (result) {
                is ReferrerFetchResult.Success -> {
                    val code = InstallReferrerParser.parse(result.referrer)
                    save {
                        put(
                            StorageKeys.INSTALL_REFERRER_STATE,
                            (if (code != null) InstallReferrerState.PENDING else InstallReferrerState.NONE).value,
                        )
                        put(StorageKeys.INSTALL_REFERRER_CODE, code)
                    }
                    GoAlongLog.log("Install Referrer: ${if (code != null) "pending $code" else "none"}")
                }
                ReferrerFetchResult.Unavailable ->
                    save { put(StorageKeys.INSTALL_REFERRER_STATE, InstallReferrerState.NONE.value) }
                ReferrerFetchResult.RetryLater ->
                    GoAlongLog.log("Install Referrer: 取得できず。次回起動で再試行")
            }
        }
        // 既に RC の ID があるか、入口が表示済みなら、ここで適用する
        if (revenueCatUserId != null || entryShown.get()) tryApplyPendingReferrer()
    }

    /** 入口の UI が表示された。保留中の Install Referrer を適用する */
    suspend fun onReferralEntryShown() {
        entryShown.set(true)
        tryApplyPendingReferrer()
    }

    /**
     * 保留中の Install Referrer のコードを適用する。通信失敗なら pending のまま次の機会に再試行。
     * @return この呼び出しで送った apply の結果(送らなかった・失敗したら null)
     */
    suspend fun tryApplyPendingReferrer(rcAppUserId: String? = null): ApplyResult? {
        if (installReferrerState != InstallReferrerState.PENDING) return null
        // 購読判定はロックの外で行い、ロックを取ってから状態を確認し直す
        val subscriber = isActiveSubscriber()
        return try {
            serial { applyPendingReferrerLocked(subscriber, rcAppUserId) }
        } catch (e: GoAlongException) {
            GoAlongLog.log("Install Referrer の適用に失敗(pending のまま): $e")
            null
        }
    }

    private suspend fun applyPendingReferrerLocked(subscriber: Boolean, rcAppUserId: String?): ApplyResult? {
        if (installReferrerState != InstallReferrerState.PENDING) return null
        val code = store.getString(StorageKeys.INSTALL_REFERRER_CODE)
        if (code == null) {
            save { put(StorageKeys.INSTALL_REFERRER_STATE, InstallReferrerState.NONE.value) }
            return null
        }
        // 手入力などで別の紹介が先に適用済みなら上書きしない
        if (hasAppliedReferral) {
            markIneligible(code, GoAlongEvent.InstallReferrerIneligible.Reason.ALREADY_REFERRED)
            return null
        }
        if (subscriber) {
            markIneligible(code, GoAlongEvent.InstallReferrerIneligible.Reason.EXISTING_SUBSCRIBER)
            return null
        }
        // 通信・保存の失敗は呼び出し元で握り、pending のまま残す
        val result = applyLocked(code, ApplyMethod.INSTALL_REFERRER, rcAppUserId, confirmReplace = false)
        when (result) {
            is ApplyResult.Applied -> markApplied(code, result.perk, result.offeringKey)
            is ApplyResult.AlreadyApplied -> markApplied(code, result.perk, result.offeringKey)
            is ApplyResult.Conflict ->
                markIneligible(code, GoAlongEvent.InstallReferrerIneligible.Reason.ALREADY_REFERRED)
            ApplyResult.ExistingSubscriber ->
                markIneligible(code, GoAlongEvent.InstallReferrerIneligible.Reason.EXISTING_SUBSCRIBER)
            is ApplyResult.NotFound -> markIneligible(
                code,
                if (result.error == CodeErrorKind.EXPIRED) {
                    GoAlongEvent.InstallReferrerIneligible.Reason.EXPIRED
                } else {
                    GoAlongEvent.InstallReferrerIneligible.Reason.NOT_FOUND
                },
            )
        }
        return result
    }

    private fun markApplied(code: String, perk: GoAlongPerk?, offeringKey: String?) {
        save { put(StorageKeys.INSTALL_REFERRER_STATE, InstallReferrerState.APPLIED.value) }
        GoAlongLog.log("Install Referrer: applied $code")
        emit(GoAlongEvent.InstallReferrerApplied(code, perk, offeringKey))
    }

    private fun markIneligible(code: String, reason: GoAlongEvent.InstallReferrerIneligible.Reason) {
        save { put(StorageKeys.INSTALL_REFERRER_STATE, InstallReferrerState.INELIGIBLE.value) }
        GoAlongLog.log("Install Referrer: ineligible $code ($reason)")
        emit(GoAlongEvent.InstallReferrerIneligible(code, reason))
    }

    // MARK: - 内部

    private suspend fun <T> serial(block: suspend () -> T): T =
        mutex.withLock { withContext(dispatcher) { block() } }

    /**
     * 紹介の状態を消す。[suppressAutoApply] なら(利用者の取り消し)、Install Referrer の自動適用も
     * 取得前(unread)・保留中(pending)を問わず永久に止める。どちらも 1 回の保存にまとめる
     */
    private fun clearReferral(suppressAutoApply: Boolean = false) {
        val referrerState = installReferrerState
        val suppress = suppressAutoApply &&
            (referrerState == InstallReferrerState.UNREAD || referrerState == InstallReferrerState.PENDING)
        val pendingCode = store.getString(StorageKeys.INSTALL_REFERRER_CODE)
        save {
            put(StorageKeys.REFERRAL_APPLIED, false)
            put(StorageKeys.REFERRAL_OFFERING_KEY, null)
            put(StorageKeys.REFERRAL_PERK_LABEL, null)
            putCachedPerk(null)
            if (suppress) put(StorageKeys.INSTALL_REFERRER_STATE, InstallReferrerState.INELIGIBLE.value)
        }
        if (suppress) {
            GoAlongLog.log("Install Referrer: 取り消されたので自動適用を止める")
            if (referrerState == InstallReferrerState.PENDING && pendingCode != null) {
                emit(
                    GoAlongEvent.InstallReferrerIneligible(
                        pendingCode,
                        GoAlongEvent.InstallReferrerIneligible.Reason.REMOVED,
                    ),
                )
            }
        }
    }

    /** 保存に成功したときだけ反映する。失敗は [GoAlongException.Storage] */
    private fun save(block: MutableMap<String, Any?>.() -> Unit) {
        try {
            store.edit(block)
        } catch (e: StorageException) {
            GoAlongLog.log("保存に失敗: $e")
            throw GoAlongException.Storage(e)
        }
    }

    private fun MutableMap<String, Any?>.putCachedPerk(perk: GoAlongPerk?) {
        put(StorageKeys.APPLIED_PERK_LABEL, perk?.label)
        put(StorageKeys.APPLIED_PERK_ENTITLEMENT, perk?.entitlement)
        put(StorageKeys.APPLIED_PERK_EXPIRES_AT_MS, perk?.expiresAtMs)
    }

    private fun putDeviceMeta(body: JSONObject) {
        body.put("sdkVersion", sdkVersion)
        body.put("osVersion", device.osVersion)
        device.appVersion?.let { body.put("appVersion", it) }
        body.put("locale", device.locale)
        body.put("platform", device.platform)
    }

    private suspend fun reportInstallationIfNeeded() {
        if (store.getBoolean(StorageKeys.INSTALLATION_REPORTED)) return
        try {
            val body = JSONObject().put("appId", appId).put("installationId", installationId)
            putDeviceMeta(body)
            val json = withContext(dispatcher) { request("installations", body) }
            if (json.optBoolean("ok", false)) save { put(StorageKeys.INSTALLATION_REPORTED, true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 未報告のまま残し、次回起動の configure で再試行する
            GoAlongLog.log("installation の報告に失敗: $e")
        }
    }

    private suspend fun request(
        path: String,
        body: JSONObject? = null,
        query: Map<String, String> = emptyMap(),
    ): JSONObject {
        val url = buildString {
            append(baseUrl).append("/api/sdk/v1/").append(path)
            if (query.isNotEmpty()) {
                append('?')
                append(query.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" })
            }
        }
        val request = HttpRequest(if (body == null) "GET" else "POST", url, body?.toString())
        val response = try {
            withTimeout(timeoutMs) { transport.execute(request) }
        } catch (e: TimeoutCancellationException) {
            throw GoAlongException.Network(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GoAlongException) {
            throw e
        } catch (e: Exception) {
            throw GoAlongException.Network(e)
        }
        if (response.statusCode !in 200..299) throw GoAlongException.Server(response.statusCode)
        return try {
            JSONObject(response.body)
        } catch (e: JSONException) {
            throw GoAlongException.Network(e)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    internal companion object {
        fun parseCreator(json: JSONObject?): GoAlongCreator? {
            json ?: return null
            return GoAlongCreator(
                displayName = json.stringOrNull("displayName") ?: "",
                avatarUrl = json.stringOrNull("avatarUrl"),
            )
        }

        fun parsePerk(json: JSONObject?): GoAlongPerk? {
            json ?: return null
            val label = json.stringOrNull("label") ?: return null
            return GoAlongPerk(
                label = label,
                entitlement = json.stringOrNull("entitlement"),
                expiresAtMs = json.longOrNull("expiresAtMs"),
                duration = json.stringOrNull("duration"),
            )
        }

        /** ISO 8601(ミリ秒あり・なし、Z または ±hh:mm)。読めなければ null */
        fun parseIsoDate(value: String?): Long? {
            value ?: return null
            for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX")) {
                val format = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }
                try {
                    return format.parse(value)?.time
                } catch (_: ParseException) {
                }
            }
            return null
        }
    }
}

internal fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else opt(key) as? String

internal fun JSONObject.longOrNull(key: String): Long? =
    if (isNull(key)) null else (opt(key) as? Number)?.toLong()
