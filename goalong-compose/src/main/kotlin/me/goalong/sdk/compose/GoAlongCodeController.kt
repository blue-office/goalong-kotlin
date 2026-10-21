package me.goalong.sdk.compose

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.goalong.sdk.ApplyMethod
import me.goalong.sdk.ApplyResult
import me.goalong.sdk.CodeErrorKind
import me.goalong.sdk.GoAlongCreator
import me.goalong.sdk.GoAlongPerk
import me.goalong.sdk.GoAlongReferralSnapshot
import kotlin.coroutines.cancellation.CancellationException

/** 失敗の種類。文言はアプリ側(または既成 UI の [Labels])で決める */
public enum class GoAlongCodeIssue {
    INVALID_FORMAT,
    NOT_FOUND,

    /** コードの有効期限切れ */
    EXPIRED,

    /** 別の紹介が適用済み。`apply(confirmReplace = true)` で上書きできる */
    CONFLICT,

    /** すでに有効な購読があるため紹介の対象外 */
    EXISTING_SUBSCRIBER,
    NETWORK,
}

/** 入力の段階(iOS の CreatorCodeModel.Phase と同じ) */
public sealed interface GoAlongCodePhase {
    public data object Idle : GoAlongCodePhase

    /** 検証(プレビュー)中 */
    public data object Checking : GoAlongCodePhase

    /** コードが実在する(適用前のプレビュー)。perk は検証時の特典 */
    public data class Valid(val perk: GoAlongPerk?) : GoAlongCodePhase

    public data object Applying : GoAlongCodePhase

    public data class Failed(val issue: GoAlongCodeIssue) : GoAlongCodePhase

    /** 適用完了。perk は**実際に付与された**特典(未付与なら null) */
    public data class Done(val perk: GoAlongPerk?) : GoAlongCodePhase
}

/** [GoAlongCodeController.state] の中身 */
public data class GoAlongCodeState(
    val code: String = "",
    /** このコードを受け取った経路。紹介リンクの貼り付けは CLIPBOARD */
    val method: ApplyMethod = ApplyMethod.CODE,
    val phase: GoAlongCodePhase = GoAlongCodePhase.Idle,
    /** 特典のあるコードで適用したが、RevenueCat の ID が未設定で特典が後から付く(「ログイン後に受け取れます」) */
    val perkPending: Boolean = false,
    /** サーバー上の現在の紹介状態(null = 取得前) */
    val current: GoAlongReferralSnapshot? = null,
    val isLoadingCurrent: Boolean = false,
    /** API のメタデータ。既成 UI では紹介者名を表示しない */
    val creator: GoAlongCreator? = null,
) {
    val isBusy: Boolean
        get() = isLoadingCurrent || phase == GoAlongCodePhase.Checking || phase == GoAlongCodePhase.Applying

    /**
     * 「このコードを使う」を出すか。紹介リンクの貼り付けはコード全体が一度に来るので自動で適用するが、
     * 手入力は入力停止 ≠ 入力完了なので明示的に確定してもらう
     */
    val needsConfirmation: Boolean
        get() = phase is GoAlongCodePhase.Valid && method != ApplyMethod.CLIPBOARD
}

/**
 * 紹介コード入力の状態機械(headless)。既成 UI([GoAlongCodeInput])の内部でも使っており、
 * デザインを完全に自作したい場合はこのコントローラに独自の UI を被せる。
 *
 * - 3 文字以上の入力は 400ms 待って検証(プレビュー)する
 * - 紹介リンクを入力欄に貼り付けるとコードだけを取り出し、検証後に CLIPBOARD として自動で適用する
 * - 古い応答(入力の変更・新しい検証・適用の開始・reset より前)は採用しない
 *
 * メインスレッドから使う。不要になったら [dispose] を呼ぶ。
 *
 * ```kotlin
 * val scope = rememberCoroutineScope()
 * val controller = remember { GoAlongCodeController(scope) }
 * val state by controller.state.collectAsState()
 * TextField(state.code, controller::onCodeChange)
 * ```
 */
public class GoAlongCodeController internal constructor(
    private val scope: CoroutineScope,
    private val rcAppUserId: String?,
    private val api: GoAlongCodeApi,
    initialCode: String,
    initialMethod: ApplyMethod,
) {
    /**
     * @param scope 検証の待ち時間・自動適用に使う(Compose では rememberCoroutineScope)
     * @param rcAppUserId 省略時は GoAlong.setRevenueCatUserId で保存した ID
     */
    public constructor(
        scope: CoroutineScope,
        rcAppUserId: String? = null,
        initialCode: String = "",
        initialMethod: ApplyMethod = ApplyMethod.CODE,
    ) : this(scope, rcAppUserId, DefaultGoAlongCodeApi, initialCode, initialMethod)

    private enum class RetryAction { VALIDATE, APPLY }

    private val mutableState = MutableStateFlow(GoAlongCodeState())
    public val state: StateFlow<GoAlongCodeState> = mutableState.asStateFlow()
    public val phase: GoAlongCodePhase get() = mutableState.value.phase

    private var revision = 0
    private var currentRevision = 0
    private var debounceJob: Job? = null
    private var disposed = false
    private var retryAction = RetryAction.VALIDATE
    private var retryConfirmReplace = false
    private var previewPerk: GoAlongPerk? = null

    init {
        setCode(initialCode, initialMethod)
    }

    /** 入力欄の変更(TextField の onValueChange) */
    public fun onCodeChange(value: String) {
        setCode(value, ApplyMethod.CODE)
    }

    /** DEEP_LINK・CLIPBOARD など、コードを受け取った経路を指定して入れる */
    public fun fill(value: String, method: ApplyMethod = ApplyMethod.CODE) {
        setCode(value, method)
    }

    private fun setCode(value: String, method: ApplyMethod) {
        if (disposed) return
        val s = mutableState.value
        // 完了後・適用中は編集で状態を壊さない
        if (s.phase == GoAlongCodePhase.Applying || s.phase is GoAlongCodePhase.Done) return
        val next: String
        val nextMethod: ApplyMethod
        if (value.contains("://")) {
            // 紹介リンクを貼り付けられたらコードだけを取り出す(URL を打ち込む人はいないのでペースト由来とみなす)
            val extracted = api.extractCode(value)
            if (extracted == null) {
                revision++
                cancelDebounce()
                previewPerk = null
                retryAction = RetryAction.VALIDATE
                mutableState.update {
                    it.copy(
                        code = value,
                        method = ApplyMethod.CODE,
                        phase = GoAlongCodePhase.Failed(GoAlongCodeIssue.INVALID_FORMAT),
                        perkPending = false,
                        creator = null,
                    )
                }
                return
            }
            next = extracted
            nextMethod = ApplyMethod.CLIPBOARD
        } else {
            next = value
            nextMethod = method
        }
        if (next == s.code && nextMethod == s.method) return
        revision++
        cancelDebounce()
        previewPerk = null
        mutableState.update {
            it.copy(
                code = next,
                method = nextMethod,
                phase = GoAlongCodePhase.Idle,
                perkPending = false,
                creator = null,
            )
        }
        scheduleValidation()
    }

    private fun cancelDebounce() {
        debounceJob?.cancel()
        debounceJob = null
    }

    private fun scheduleValidation() {
        cancelDebounce()
        val s = mutableState.value
        if (disposed || s.isLoadingCurrent || s.phase != GoAlongCodePhase.Idle || s.code.trim().length < 3) return
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            debounceJob = null
            validatePreview()
        }
    }

    /** 初期表示用に現在の紹介状態を取得する。端末に残っている適用済みの状態を先に出す。失敗は表示しない */
    public suspend fun loadCurrentReferral() {
        val s = mutableState.value
        if (disposed || s.isLoadingCurrent || s.phase == GoAlongCodePhase.Applying || s.phase is GoAlongCodePhase.Done) {
            return
        }
        if (mutableState.value.current == null) {
            api.localAppliedSnapshot()?.let { local -> mutableState.update { it.copy(current = local) } }
        }
        cancelDebounce()
        val myRevision = ++currentRevision
        mutableState.update { it.copy(isLoadingCurrent = true) }
        try {
            val snapshot = api.currentReferral()
            if (!disposed && myRevision == currentRevision) mutableState.update { it.copy(current = snapshot) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 状態の取得に失敗しても入力はできる(iOS と同じく黙って続ける)
        } finally {
            if (!disposed && myRevision == currentRevision) {
                mutableState.update { it.copy(isLoadingCurrent = false) }
                scheduleValidation()
            }
        }
    }

    /** 適用せずコードを検証し、特典のプレビューを phase に反映する。紹介リンク由来なら続けて適用する */
    public suspend fun validatePreview() {
        val s = mutableState.value
        if (disposed || s.isLoadingCurrent || s.phase == GoAlongCodePhase.Applying ||
            s.phase is GoAlongCodePhase.Done || s.code.isBlank()
        ) {
            return
        }
        cancelDebounce()
        val myRevision = ++revision
        previewPerk = null
        retryAction = RetryAction.VALIDATE
        mutableState.update { it.copy(phase = GoAlongCodePhase.Checking) }
        try {
            val validation = api.validateCode(s.code.trim())
            if (disposed || myRevision != revision) return
            if (validation.valid) {
                previewPerk = validation.perk
                mutableState.update { it.copy(phase = GoAlongCodePhase.Valid(validation.perk), creator = validation.creator) }
            } else {
                fail(issueFor(validation.error))
            }
        } catch (e: CancellationException) {
            // 画面を離れるなどで取り消されたら、検証中のまま残さない(再表示で入力・検証できるように)
            if (!disposed && myRevision == revision) {
                revision++
                mutableState.update { it.copy(phase = GoAlongCodePhase.Idle) }
            }
            throw e
        } catch (_: Exception) {
            // 無言で idle に戻すと「入力したのに何も起きない」に見える。入力は残して失敗を出す
            if (disposed || myRevision != revision) return
            fail(GoAlongCodeIssue.NETWORK)
        }
        val now = mutableState.value
        if (!disposed && myRevision == revision && now.phase is GoAlongCodePhase.Valid &&
            now.method == ApplyMethod.CLIPBOARD
        ) {
            apply()
        }
    }

    /**
     * コードを適用する。conflict のときは Failed(CONFLICT) になるので、確認の UI を出して
     * `apply(confirmReplace = true)` を再実行する
     */
    public suspend fun apply(confirmReplace: Boolean = false): ApplyResult? {
        val s = mutableState.value
        if (disposed || s.isLoadingCurrent || s.phase == GoAlongCodePhase.Applying ||
            s.phase is GoAlongCodePhase.Done || s.code.isBlank()
        ) {
            return null
        }
        cancelDebounce()
        val myRevision = ++revision
        retryAction = RetryAction.APPLY
        retryConfirmReplace = confirmReplace
        mutableState.update { it.copy(phase = GoAlongCodePhase.Applying) }
        val result = try {
            api.applyCode(s.code.trim(), s.method, rcAppUserId, confirmReplace)
        } catch (e: CancellationException) {
            // 取り消されても適用中のまま残さない(ViewModel などで持ったコントローラを再表示すると、
            // 編集・再適用・reset がすべて拒否されてしまう)。サーバー側で適用済みになったかもしれないので、
            // 次の loadCurrentReferral で現在の状態を取り直す
            if (!disposed && myRevision == revision) {
                revision++
                mutableState.update { it.copy(phase = GoAlongCodePhase.Idle, current = null) }
            }
            throw e
        } catch (_: Exception) {
            if (!disposed && myRevision == revision) fail(GoAlongCodeIssue.NETWORK)
            return null
        }
        if (disposed || myRevision != revision) return null
        when (result) {
            is ApplyResult.Applied -> complete(result.creator, result.perk, result.perkLabel)
            is ApplyResult.AlreadyApplied -> complete(result.creator, result.perk, result.perkLabel)
            is ApplyResult.Conflict -> fail(GoAlongCodeIssue.CONFLICT)
            ApplyResult.ExistingSubscriber -> fail(GoAlongCodeIssue.EXISTING_SUBSCRIBER)
            is ApplyResult.NotFound -> fail(issueFor(result.error))
        }
        return result
    }

    /** 通信に失敗した操作を再試行する。置換の同意と入力経路は保持する */
    public suspend fun retry() {
        val s = mutableState.value
        if (disposed || s.isBusy || s.phase != GoAlongCodePhase.Failed(GoAlongCodeIssue.NETWORK)) return
        when (retryAction) {
            RetryAction.VALIDATE -> validatePreview()
            RetryAction.APPLY -> apply(confirmReplace = retryConfirmReplace)
        }
    }

    /** 状態を初期化する(画面を開き直すときなど)。適用中は何もしない */
    public fun reset() {
        if (disposed || mutableState.value.phase == GoAlongCodePhase.Applying) return
        cancelDebounce()
        revision++
        currentRevision++
        previewPerk = null
        retryAction = RetryAction.VALIDATE
        retryConfirmReplace = false
        mutableState.update {
            GoAlongCodeState(current = it.current)
        }
    }

    /** 以後の応答を捨て、通知しない */
    public fun dispose() {
        cancelDebounce()
        disposed = true
        revision++
        currentRevision++
    }

    /** ラベルだけでは付与済みとは扱わず、RC の ID が未設定のときだけ「ログイン後に受け取れます」にする */
    private fun complete(creator: GoAlongCreator?, perk: GoAlongPerk?, perkLabel: String?) {
        val pending = perk == null &&
            (previewPerk != null || perkLabel != null) &&
            rcAppUserId.isNullOrEmpty() &&
            !api.hasRevenueCatUserId()
        mutableState.update {
            it.copy(phase = GoAlongCodePhase.Done(perk), perkPending = pending, creator = creator)
        }
    }

    private fun fail(issue: GoAlongCodeIssue) {
        mutableState.update { it.copy(phase = GoAlongCodePhase.Failed(issue)) }
    }

    private fun issueFor(kind: CodeErrorKind?): GoAlongCodeIssue = when (kind) {
        CodeErrorKind.INVALID_FORMAT -> GoAlongCodeIssue.INVALID_FORMAT
        CodeErrorKind.EXPIRED -> GoAlongCodeIssue.EXPIRED
        else -> GoAlongCodeIssue.NOT_FOUND
    }

    internal companion object {
        const val DEBOUNCE_MS = 400L
    }
}
