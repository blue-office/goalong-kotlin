package me.goalong.sdk

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/**
 * GoAlong SDK — インフルエンサーの紹介コードによる attribution(Android 版)。
 *
 * ```kotlin
 * // Application.onCreate
 * GoAlong.configure(this, appId = "…", appSlug = "myapp")
 * // RevenueCat の configure / logIn の直後
 * lifecycleScope.launch { GoAlong.setRevenueCatUserId(Purchases.sharedInstance.appUserID) }
 * ```
 */
public object GoAlong {
    /** サーバーに送る SDK のバージョン */
    public val sdkVersion: String get() = BuildConfig.SDK_VERSION

    @Volatile private var client: GoAlongClient? = null
    @Volatile private var clientScope: CoroutineScope? = null

    private val mutableEvents = MutableSharedFlow<GoAlongEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * SDK からの通知(Install Referrer による自動適用の結果・後追い特典の付与)。
     * 起動直後に届くことがあるので、Application のスコープなどで早めに collect する
     */
    public val events: SharedFlow<GoAlongEvent> = mutableEvents.asSharedFlow()

    /** 詳細ログ(Logcat のタグ "GoAlong")。本番では false のままにする */
    public var verboseLogging: Boolean
        get() = GoAlongLog.enabled
        set(value) {
            GoAlongLog.enabled = value
        }

    /**
     * SDK を初期化する。Application.onCreate で 1 回呼ぶ。
     * installation の報告(失敗は次回起動で再送)と Install Referrer の読み取りを裏で始める。
     *
     * @param context Application Context だけを保持する
     * @param appSlug 中間 LP の slug(例: "booknotion")。指定すると、自アプリの slug の紹介リンクだけを受け付ける
     * @param isActiveSubscriber いま有効な購読があるか(RevenueCat の CustomerInfo などで判定)。
     *   true なら紹介を適用しない・入口を出さない。後から [setActiveSubscriberProvider] でも設定できる
     */
    @JvmStatic
    @JvmOverloads
    public fun configure(
        context: Context,
        appId: String,
        appSlug: String? = null,
        baseUrl: String = "https://goalong.me",
        verboseLogging: Boolean = false,
        isActiveSubscriber: (suspend () -> Boolean)? = null,
    ) {
        val appContext = context.applicationContext ?: context
        GoAlongLog.enabled = verboseLogging
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val next = GoAlongClient(
            appId = appId,
            appSlug = appSlug,
            baseUrl = baseUrl,
            store = JsonFileStore(File(appContext.noBackupFilesDir, JsonFileStore.FILE_NAME)),
            transport = UrlConnectionTransport(),
            device = DeviceInfo.from(appContext),
            referrerSource = PlayInstallReferrerSource(appContext),
            subscriberCheck = isActiveSubscriber ?: client?.subscriberCheck,
            scope = scope,
            emit = { mutableEvents.tryEmit(it) },
        )
        install(next, scope)
        // installationId は初回の保存に失敗すると例外を投げる。ログのために configure を落とさない
        GoAlongLog.log("configure appId=$appId installationId=${runCatching { next.installationId }.getOrNull()}")
        next.start()
    }

    /** テスト・内部用: 構築済みのクライアントに差し替える */
    internal fun install(next: GoAlongClient?, scope: CoroutineScope?) {
        val previous = clientScope
        client = next
        clientScope = scope
        previous?.cancel()
    }

    private fun requireClient(): GoAlongClient = client ?: throw GoAlongException.NotConfigured()

    /** configure 済みか */
    public val isConfigured: Boolean get() = client != null

    /** 購読判定を設定・変更する(null で解除) */
    @JvmStatic
    public fun setActiveSubscriberProvider(provider: (suspend () -> Boolean)?) {
        requireClient().subscriberCheck = provider
    }

    /** アプリが渡した判定で、いま有効な購読があるか(未設定・失敗は false) */
    public suspend fun isActiveSubscriber(): Boolean = requireClient().isActiveSubscriber()

    /**
     * RevenueCat の App User ID を知らせる。**`Purchases.configure` / `logIn` の直後に呼ぶ。**
     *
     * 以後 [applyCode] に毎回 rcAppUserId を渡す必要がなくなり、保留中の Install Referrer の
     * コードを適用し、紹介済みで特典が未付与なら特典を取りに行く。
     * @return 付与された特典(無ければ null。通信失敗も null で、次の呼び出しで再試行される)
     */
    public suspend fun setRevenueCatUserId(id: String): GoAlongPerk? = requireClient().setRevenueCatUserId(id)

    /** 直近に設定された RevenueCat の App User ID(未設定なら null) */
    public val revenueCatUserId: String? get() = client?.revenueCatUserId

    /** 端末ごとの installation ID(UUID 小文字。再インストールで作り直す)。初回に保存できなければ [GoAlongException.Storage] */
    public val installationId: String get() = requireClient().installationId

    /**
     * RevenueCat の Subscriber Attributes に渡す属性(`Purchases.sharedInstance.setAttributes(...)`)。
     * `goalong_installation_id`、紹介済みなら `goalong_referred = "true"`、
     * intro offer 特典があれば `goalong_offering`。
     * installation ID を保存できなければ [GoAlongException.Storage]
     */
    public fun attributionAttributes(): Map<String, String> = requireClient().attributionAttributes()

    /** この端末で紹介が適用済みか(ローカル判定・即時・オフライン可) */
    public val hasAppliedReferralLocally: Boolean get() = client?.hasAppliedReferral ?: false

    /** 付与済み特典の端末キャッシュ(期限切れは null)。正はサーバー([currentReferral]) */
    public val cachedAppliedPerk: GoAlongPerk? get() = client?.cachedAppliedPerk

    /** Install Referrer の処理状態 */
    public val installReferrerState: InstallReferrerState
        get() = client?.installReferrerState ?: InstallReferrerState.UNREAD

    /** コードを適用せず検証する(入力 UI のプレビュー用) */
    public suspend fun validateCode(code: String): CodeValidation = requireClient().validateCode(code)

    /**
     * コードを適用して attribution を確定する。
     * @param code コード、または紹介リンク(`https://goalong.me/{appSlug}/{CODE}`)
     * @param rcAppUserId 省略時は [setRevenueCatUserId] で保存した ID
     * @param confirmReplace 別の紹介が適用済み([ApplyResult.Conflict])のとき、確認後に true で再実行する
     */
    public suspend fun applyCode(
        code: String,
        method: ApplyMethod = ApplyMethod.CODE,
        rcAppUserId: String? = null,
        confirmReplace: Boolean = false,
    ): ApplyResult = requireClient().applyCode(code, method, rcAppUserId, confirmReplace)

    /** App Link / カスタム URL からコードを取り出す(適用はしない) */
    public fun handleDeepLink(uri: Uri): String? = extractCode(uri.toString())

    /** 紹介リンクの文字列からコードを取り出す(cross-platform 仕様 §4。適用はしない) */
    public fun extractCode(text: String): String? {
        val current = client
        return if (current != null) current.extractCode(text) else ReferralLink.extract(text)
    }

    /** 紹介の現在状態を取得する(設定画面の表示などに使う) */
    public suspend fun currentReferral(): GoAlongReferralSnapshot = requireClient().currentReferral()

    /**
     * 紹介を取り消す。以後の紹介帰属が止まる(受け取り済みの特典は期限まで維持)。
     * 取り消した後に別のコードを入力して再適用はできる。Install Referrer による再適用はしない。
     * @return 取り消せたら true(適用中の紹介が無ければ false)
     */
    public suspend fun removeReferral(): Boolean = requireClient().removeReferral()

    /**
     * アプリ内に紹介コードの入口を出してよいか(受付中のコードが無い期間は false)。
     * サーバー指定の期間メモリにキャッシュする。失敗時は直近の結果、無ければ false
     */
    public suspend fun referralEntryEnabled(): Boolean = client?.referralEntryEnabled() ?: false

    /**
     * 紹介コードの入口が表示されたことを知らせる(goalong-compose の既成 UI は自動で呼ぶ)。
     * 独自 UI を作る場合に呼ぶと、保留中の Install Referrer のコードを適用する
     */
    public suspend fun onReferralEntryShown() {
        client?.onReferralEntryShown()
    }
}
