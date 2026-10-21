package me.goalong.sdk

/** 紹介者(インフルエンサー)の情報。既成 UI では表示しない(誰の紹介かはユーザーに見せない) */
public data class GoAlongCreator(
    val displayName: String,
    val avatarUrl: String?,
)

/** コードが使えない理由 */
public enum class CodeErrorKind(public val value: String) {
    INVALID_FORMAT("invalid_format"),
    NOT_FOUND("not_found"),

    /** リンク・コードの有効期限切れ(キャンペーンのリンク有効期間を超過) */
    EXPIRED("expired");

    internal companion object {
        fun from(value: String?): CodeErrorKind? = entries.firstOrNull { it.value == value }
    }
}

/**
 * コードの適用でユーザーが受け取れる特典。
 *
 * @property label 表示用の文言(例: 「Pro を 1 週間無料」)
 * @property entitlement 付与される entitlement の識別子(付与前・状態取得の結果では null のことがある)
 * @property expiresAtMs 失効時刻(epoch ms)。付与前(検証のプレビュー等)は null
 * @property duration 検証のプレビューで返る期間の種類(例: "weekly")
 */
public data class GoAlongPerk(
    val label: String,
    val entitlement: String? = null,
    val expiresAtMs: Long? = null,
    val duration: String? = null,
)

/** [GoAlong.validateCode] の結果 */
public data class CodeValidation(
    val valid: Boolean,
    val error: CodeErrorKind?,
    /** 検証成功時の正規化済みコード(大文字) */
    val code: String?,
    val creator: GoAlongCreator?,
    /** このコードに紐づくユーザー特典(なければ null) */
    val perk: GoAlongPerk?,
)

/** コードを受け取った経路(統計用。不正防止の根拠にはしない) */
public enum class ApplyMethod(public val value: String) {
    CODE("CODE"),
    DEEP_LINK("DEEP_LINK"),
    CLIPBOARD("CLIPBOARD"),

    /** Google Play の Install Referrer から SDK が自動で適用した */
    INSTALL_REFERRER("INSTALL_REFERRER"),
}

/** [GoAlong.applyCode] の結果(iOS の ApplyResult と同じ 5 種) */
public sealed interface ApplyResult {
    /**
     * @property offeringKey intro offer 特典のとき、RevenueCat の Offering 識別子
     * @property perkLabel 特典の表示名。これだけでは付与済みを意味しない(付与済みは [perk])
     */
    public data class Applied(
        val creator: GoAlongCreator?,
        val perk: GoAlongPerk? = null,
        val offeringKey: String? = null,
        val perkLabel: String? = null,
    ) : ApplyResult

    public data class AlreadyApplied(
        val creator: GoAlongCreator?,
        val perk: GoAlongPerk? = null,
        val offeringKey: String? = null,
        val perkLabel: String? = null,
    ) : ApplyResult

    /** 別の紹介が適用済み。confirmReplace = true で再実行すると上書きされる */
    public data class Conflict(val currentCreator: GoAlongCreator?) : ApplyResult

    /** すでに有効な購読があるため紹介を適用できない */
    public data object ExistingSubscriber : ApplyResult

    /** コードが見つからない・形式不正・期限切れ */
    public data class NotFound(val error: CodeErrorKind?) : ApplyResult
}

/** 紹介の現在状態(installation 単位) */
public sealed interface GoAlongReferralSnapshot {
    /** 紹介は適用されていない */
    public data object None : GoAlongReferralSnapshot

    /** 適用中。perk は**実際に付与された**特典(未付与・期限切れなら null) */
    public data class Applied(
        val creator: GoAlongCreator?,
        val appliedAtMs: Long?,
        val perk: GoAlongPerk?,
    ) : GoAlongReferralSnapshot

    /** ユーザーが取り消した(明示的な再適用は可能) */
    public data class Removed(val removedAtMs: Long?) : GoAlongReferralSnapshot
}

/** SDK のエラー(cross-platform 仕様 §6) */
public sealed class GoAlongException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** configure 前に呼んだ */
    public class NotConfigured : GoAlongException("GoAlong.configure() has not been called")

    /** 通信失敗・タイムアウト・応答の解析失敗 */
    public class Network(cause: Throwable? = null) : GoAlongException("GoAlong network error", cause)

    /**
     * 端末への保存に失敗した(空き容量不足など)。保存できなかった状態はサーバーにも送らない。
     * 紹介の適用自体はサーバーで成立していることがあるので、後で [GoAlong.currentReferral] で確かめる
     */
    public class Storage(cause: Throwable? = null) : GoAlongException("GoAlong storage error", cause)

    /** 2xx 以外の応答 */
    public class Server(public val statusCode: Int) : GoAlongException("GoAlong server error ($statusCode)")
}

/** Install Referrer の処理状態(計画書 §2-3) */
public enum class InstallReferrerState(internal val value: String) {
    /** まだ読めていない(次回起動で再試行) */
    UNREAD("unread"),

    /** referrer なし・紹介コードなし・取得不能 */
    NONE("none"),

    /** コードを読み取り、適用の機会を待っている */
    PENDING("pending"),

    /** 自動で適用した */
    APPLIED("applied"),

    /** 適用できない(購読中・期限切れ・見つからない・別の紹介が適用済み・取消済み) */
    INELIGIBLE("ineligible");

    internal companion object {
        fun from(value: String?): InstallReferrerState = entries.firstOrNull { it.value == value } ?: UNREAD
    }
}

/** SDK からアプリへの通知([GoAlong.events]) */
public sealed interface GoAlongEvent {
    /** Install Referrer のコードを自動で適用した。RevenueCat の属性を設定し直すこと */
    public data class InstallReferrerApplied(
        val code: String,
        val perk: GoAlongPerk?,
        val offeringKey: String?,
    ) : GoAlongEvent

    /** Install Referrer のコードは適用できなかった(恒久的な理由) */
    public data class InstallReferrerIneligible(
        val code: String,
        val reason: Reason,
    ) : GoAlongEvent {
        public enum class Reason { ALREADY_REFERRED, EXISTING_SUBSCRIBER, EXPIRED, NOT_FOUND, REMOVED }
    }

    /** 後追いの特典(ログイン後の claim)が付与された */
    public data class PerkGranted(val perk: GoAlongPerk) : GoAlongEvent
}
