package me.goalong.sdk.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color

/**
 * 既成 UI の文言。null の項目は SDK の strings.xml(英語・日本語・韓国語・繁体字中国語)から
 * 表示中の Context の言語で解決する。アプリが対応していない言語を出したくないときは、
 * アプリ側の `resourceConfigurations` で言語を絞るか、ここで上書きする。
 *
 * `%1$s` を含む文言: [perkPreview]・[perkGranted] = 特典の表示名、[perkValidUntil] = 失効日
 */
public data class Labels(
    // 入口(GoAlongReferralEntry)
    val collapsed: String? = null,
    val entryTitle: String? = null,
    /** 閉じるボタンのアクセシビリティラベル */
    val close: String? = null,
    // 入力欄(GoAlongCodeInput)
    val title: String? = null,
    val placeholder: String? = null,
    val skip: String? = null,
    val appliedNoCreator: String? = null,
    val perkPreview: String? = null,
    val errorFormat: String? = null,
    val errorNotFound: String? = null,
    val errorConflict: String? = null,
    val errorExistingSubscriber: String? = null,
    val replace: String? = null,
    val errorExpired: String? = null,
    val errorNetwork: String? = null,
    /** 適用済みカードの文言 */
    val currentReferralNoCreator: String? = null,
    val removedNotice: String? = null,
    /** 手入力時の確定ボタン(紹介リンクの貼り付けは自動で適用する) */
    val useThisCode: String? = null,
    val retry: String? = null,
    val perkGranted: String? = null,
    val perkValidUntil: String? = null,
    val perkPending: String? = null,
)

/** 文言を解決した結果 */
internal data class ResolvedLabels(
    val collapsed: String,
    val entryTitle: String,
    val close: String,
    val title: String,
    val placeholder: String,
    val skip: String,
    val appliedNoCreator: String,
    val perkPreview: String,
    val errorFormat: String,
    val errorNotFound: String,
    val errorConflict: String,
    val errorExistingSubscriber: String,
    val replace: String,
    val errorExpired: String,
    val errorNetwork: String,
    val currentReferralNoCreator: String,
    val removedNotice: String,
    val useThisCode: String,
    val retry: String,
    val perkGranted: String,
    val perkValidUntil: String,
    val perkPending: String,
) {
    fun message(issue: GoAlongCodeIssue): String = when (issue) {
        GoAlongCodeIssue.INVALID_FORMAT -> errorFormat
        GoAlongCodeIssue.NOT_FOUND -> errorNotFound
        GoAlongCodeIssue.EXPIRED -> errorExpired
        GoAlongCodeIssue.CONFLICT -> errorConflict
        GoAlongCodeIssue.EXISTING_SUBSCRIBER -> errorExistingSubscriber
        GoAlongCodeIssue.NETWORK -> errorNetwork
    }
}

/** `%1$s`(Android)と `%@`(iOS の文言を流用した場合)の両方を置き換える */
internal fun String.fill(value: String): String = replace("%1\$s", value).replace("%@", value)

@Composable
internal fun Labels.resolve(): ResolvedLabels = ResolvedLabels(
    collapsed = collapsed ?: stringResource(R.string.goalong_collapsed),
    entryTitle = entryTitle ?: stringResource(R.string.goalong_entry_title),
    close = close ?: stringResource(R.string.goalong_close),
    title = title ?: stringResource(R.string.goalong_input_title),
    placeholder = placeholder ?: stringResource(R.string.goalong_placeholder),
    skip = skip ?: stringResource(R.string.goalong_skip),
    appliedNoCreator = appliedNoCreator ?: stringResource(R.string.goalong_applied_no_creator),
    perkPreview = perkPreview ?: stringResource(R.string.goalong_perk_preview),
    errorFormat = errorFormat ?: stringResource(R.string.goalong_error_format),
    errorNotFound = errorNotFound ?: stringResource(R.string.goalong_error_not_found),
    errorConflict = errorConflict ?: stringResource(R.string.goalong_error_conflict),
    errorExistingSubscriber = errorExistingSubscriber ?: stringResource(R.string.goalong_error_existing_subscriber),
    replace = replace ?: stringResource(R.string.goalong_replace),
    errorExpired = errorExpired ?: stringResource(R.string.goalong_error_expired),
    errorNetwork = errorNetwork ?: stringResource(R.string.goalong_error_network),
    currentReferralNoCreator = currentReferralNoCreator ?: stringResource(R.string.goalong_current_referral_no_creator),
    removedNotice = removedNotice ?: stringResource(R.string.goalong_removed_notice),
    useThisCode = useThisCode ?: stringResource(R.string.goalong_use_this_code),
    retry = retry ?: stringResource(R.string.goalong_retry),
    perkGranted = perkGranted ?: stringResource(R.string.goalong_perk_granted),
    perkValidUntil = perkValidUntil ?: stringResource(R.string.goalong_perk_valid_until),
    perkPending = perkPending ?: stringResource(R.string.goalong_perk_pending),
)

/**
 * 既成 UI の見た目。[Color.Unspecified] の項目はアプリの MaterialTheme から決める。
 * 既定は控えめなモノクロ(ボタン・完了の円は onSurface 色)。ブランド色を出したいときだけ [accent] を差し替える
 */
public data class GoAlongTheme(
    /** ボタン等の色(既定: onSurface = ライトで黒・ダークで白) */
    val accent: Color = Color.Unspecified,
    val textColor: Color = Color.Unspecified,
    /** 補足・状態説明の文字色 */
    val secondaryTextColor: Color = Color.Unspecified,
    val errorTextColor: Color = Color.Unspecified,
    /** accent で塗った面(確定ボタン・完了チェックの円)の上の文字・記号色 */
    val onAccent: Color = Color.Unspecified,
    /** 見出し(入力欄のタイトル・入口の見出し) */
    val titleStyle: TextStyle? = null,
    /** 入力欄・ボタンの角丸。カード面はこれに +4dp */
    val cornerRadius: Dp = 12.dp,
    val fieldBackground: Color = Color.Unspecified,
    val fieldBorder: Color = Color.Unspecified,
    /** 適用済み・完了・展開した入口などの面 */
    val cardBackground: Color = Color.Unspecified,
)

internal data class ResolvedTheme(
    val accent: Color,
    val textColor: Color,
    val secondaryTextColor: Color,
    val errorTextColor: Color,
    val onAccent: Color,
    val titleStyle: TextStyle,
    val cornerRadius: Dp,
    val fieldBackground: Color,
    val fieldBorder: Color,
    val cardBackground: Color,
    val subtleFill: Color,
) {
    val cardCornerRadius: Dp get() = cornerRadius + 4.dp
}

@Composable
internal fun GoAlongTheme.resolve(): ResolvedTheme {
    val scheme = MaterialTheme.colorScheme
    fun Color.or(fallback: Color) = if (this == Color.Unspecified) fallback else this
    return ResolvedTheme(
        accent = accent.or(scheme.onSurface),
        textColor = textColor.or(scheme.onSurface),
        secondaryTextColor = secondaryTextColor.or(scheme.onSurfaceVariant),
        errorTextColor = errorTextColor.or(scheme.error),
        onAccent = onAccent.or(scheme.surface),
        titleStyle = titleStyle ?: MaterialTheme.typography.titleMedium,
        cornerRadius = cornerRadius,
        fieldBackground = fieldBackground.or(scheme.surface),
        fieldBorder = fieldBorder.or(scheme.onSurface.copy(alpha = 0.12f)),
        cardBackground = cardBackground.or(scheme.surfaceContainerHigh),
        subtleFill = scheme.onSurface.copy(alpha = 0.08f),
    )
}
