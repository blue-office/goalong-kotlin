package me.goalong.sdk.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.goalong.sdk.GoAlongCreator
import me.goalong.sdk.GoAlongPerk
import me.goalong.sdk.GoAlongReferralSnapshot
import java.text.DateFormat
import java.util.Date

/**
 * 紹介コード入力の既成 UI(iOS の CreatorCodeInputView と同じ動き・文言)。
 * オンボーディング・ペイウォール・設定画面のどこにでも置ける。
 *
 * - 誰の紹介かは表示しない。取り消しの UI は出さない(API のみ)
 * - 紹介リンクを貼り付けるとコードだけを取り出して自動で適用する。手入力は「このコードを使う」で確定
 * - 文言は [labels]、見た目は [theme] で差し替える。完全に自作するなら [GoAlongCodeController] を使う
 *
 * ```kotlin
 * GoAlongCodeInput(onApplied = { /* 適用完了 */ }, onSkip = { /* あとで */ })
 * ```
 */
@Composable
public fun GoAlongCodeInput(
    modifier: Modifier = Modifier,
    labels: Labels = Labels(),
    theme: GoAlongTheme = GoAlongTheme(),
    rcAppUserId: String? = null,
    showsTitle: Boolean = true,
    controller: GoAlongCodeController? = null,
    onSkip: (() -> Unit)? = null,
    onApplied: (GoAlongCreator?) -> Unit = {},
) {
    val l = labels.resolve()
    val t = theme.resolve()
    val scope = rememberCoroutineScope()
    val owned = controller == null
    val ctrl = controller ?: remember(rcAppUserId) { GoAlongCodeController(scope, rcAppUserId) }
    DisposableEffect(ctrl) {
        onDispose { if (owned) ctrl.dispose() }
    }
    val state by ctrl.state.collectAsState()
    val latestOnApplied by rememberUpdatedState(onApplied)

    // 現在の紹介状態。端末の適用フラグと特典キャッシュで先に出し、サーバーの結果で上書きする
    LaunchedEffect(ctrl) { ctrl.loadCurrentReferral() }
    LaunchedEffect(state.phase) {
        if (state.phase is GoAlongCodePhase.Done) latestOnApplied(state.creator)
    }

    val isDone = state.phase is GoAlongCodePhase.Done
    val isApplied = !isDone && state.current is GoAlongReferralSnapshot.Applied

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!isDone) CurrentReferralSection(state.current, l, t)
        if (showsTitle && !isApplied && !isDone) {
            Text(l.title, style = t.titleStyle, color = t.textColor)
        }
        if (isDone) {
            DoneCard(state, l, t)
        } else if (!isApplied) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.code,
                    onValueChange = ctrl::onCodeChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    enabled = state.phase != GoAlongCodePhase.Applying,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text(l.placeholder, color = t.secondaryTextColor) },
                    trailingIcon = if (state.isBusy) {
                        { CircularProgressIndicator(Modifier.size(18.dp), color = t.secondaryTextColor, strokeWidth = 2.dp) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { scope.launch { ctrl.apply() } }),
                    shape = RoundedCornerShape(t.cornerRadius),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = t.textColor,
                        unfocusedTextColor = t.textColor,
                        disabledTextColor = t.secondaryTextColor,
                        focusedContainerColor = t.fieldBackground,
                        unfocusedContainerColor = t.fieldBackground,
                        disabledContainerColor = t.fieldBackground,
                        focusedBorderColor = t.textColor.copy(alpha = 0.4f),
                        unfocusedBorderColor = t.fieldBorder,
                        disabledBorderColor = t.fieldBorder,
                        cursorColor = t.textColor,
                    ),
                )
                StatusLine(state, l, t, onApply = { confirm -> scope.launch { ctrl.apply(confirm) } }) {
                    scope.launch { ctrl.retry() }
                }
            }
        }
        if (onSkip != null) {
            TextButton(onClick = onSkip) {
                Text(l.skip, style = MaterialTheme.typography.bodySmall, color = t.secondaryTextColor)
            }
        }
    }
}

@Composable
private fun StatusLine(
    state: GoAlongCodeState,
    l: ResolvedLabels,
    t: ResolvedTheme,
    onApply: (confirmReplace: Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    val footnote = MaterialTheme.typography.bodySmall
    when (val phase = state.phase) {
        is GoAlongCodePhase.Valid -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 誰の紹介かは見せない。プレビューは特典と確定操作だけ
            phase.perk?.let { perk ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GiftIcon(t.textColor, 14.dp)
                    Text(l.perkPreview.fill(perk.label), style = footnote, fontWeight = FontWeight.SemiBold, color = t.textColor)
                }
            }
            if (state.needsConfirmation) {
                Button(
                    onClick = { onApply(false) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(t.cornerRadius),
                    colors = ButtonDefaults.buttonColors(containerColor = t.accent, contentColor = t.onAccent),
                ) {
                    Text(l.useThisCode, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        is GoAlongCodePhase.Failed -> when (phase.issue) {
            // 上書きは黙って行わず、明示的に選ばせる(現在の紹介者名は出さない)
            GoAlongCodeIssue.CONFLICT -> Column {
                Text(l.errorConflict, style = footnote, color = t.secondaryTextColor)
                LinkButton(l.replace, t, enabled = !state.isBusy) { onApply(true) }
            }
            GoAlongCodeIssue.NETWORK -> Column {
                Text(l.errorNetwork, style = footnote, color = t.secondaryTextColor)
                LinkButton(l.retry, t, enabled = !state.isBusy, onClick = onRetry)
            }
            else -> Text(l.message(phase.issue), style = footnote, color = t.errorTextColor)
        }
        else -> Unit
    }
}

@Composable
private fun LinkButton(text: String, t: ResolvedTheme, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = ButtonDefaults.TextButtonContentPadding) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = t.accent)
    }
}

@Composable
private fun DoneCard(state: GoAlongCodeState, l: ResolvedLabels, t: ResolvedTheme) {
    val perk = (state.phase as? GoAlongCodePhase.Done)?.perk
    val prominent = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    when {
        // 特典目的のユーザーには「受け取れたか」が最重要なので結果を主役にし、期限を補足に置く
        perk != null -> StatusCard(true, l.perkGranted.fill(perk.label), prominent, validUntil(perk, l), t)
        // 紹介は成立したが特典は後追い(未ログイン)。付与完了と同じチェックにすると誤解させるので分ける
        state.perkPending -> StatusCard(
            filled = false,
            title = l.perkPending,
            titleStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            detail = null,
            t = t,
            icon = { GiftIcon(t.textColor) },
        )
        else -> StatusCard(true, l.appliedNoCreator, prominent, null, t)
    }
}

@Composable
private fun CurrentReferralSection(current: GoAlongReferralSnapshot?, l: ResolvedLabels, t: ResolvedTheme) {
    val body = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    when (current) {
        // 適用済みは状態表示のみ(取り消しや再入力の導線は出さない)
        is GoAlongReferralSnapshot.Applied -> {
            val perk = current.perk
            if (perk != null) {
                StatusCard(true, l.perkGranted.fill(perk.label), body, validUntil(perk, l), t)
            } else {
                StatusCard(true, l.currentReferralNoCreator, body, null, t)
            }
        }
        is GoAlongReferralSnapshot.Removed ->
            Text(l.removedNotice, style = MaterialTheme.typography.bodySmall, color = t.secondaryTextColor)
        else -> Unit
    }
}

@Composable
private fun StatusCard(
    filled: Boolean,
    title: String,
    titleStyle: TextStyle,
    detail: String?,
    t: ResolvedTheme,
    icon: @Composable () -> Unit = { CheckIcon(if (filled) t.onAccent else t.textColor) },
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(t.cardBackground, RoundedCornerShape(t.cardCornerRadius))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(36.dp).background(if (filled) t.accent else t.subtleFill, CircleShape),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            Text(title, style = titleStyle, color = t.textColor)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = t.secondaryTextColor)
            }
        }
    }
}

/** 失効日の表示文(例:「2026年10月2日 まで利用できます」)。表示中の言語で日付を書く */
@Composable
private fun validUntil(perk: GoAlongPerk, l: ResolvedLabels): String? {
    val ms = perk.expiresAtMs ?: return null
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val date = DateFormat.getDateInstance(DateFormat.LONG, locale).format(Date(ms))
    return l.perkValidUntil.fill(date)
}
