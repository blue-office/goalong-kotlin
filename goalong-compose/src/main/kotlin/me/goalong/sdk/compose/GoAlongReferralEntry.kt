package me.goalong.sdk.compose

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.goalong.sdk.GoAlong
import me.goalong.sdk.GoAlongCreator
import me.goalong.sdk.GoAlongEvent

internal enum class EntryPhase {
    /** 表示可否と手がかりを確認中 */
    CHECKING,

    /** 出さない(受付なし) */
    HIDDEN,

    /** 出さない(適用済み・購読中)。クリップボードの手がかりがあっても開かない */
    HIDDEN_LOCKED,

    /** 畳んだ 1 行 */
    COLLAPSED,

    /** 入力欄を開いた状態 */
    EXPANDED,
}

/**
 * 入口を出すか・どう出すかを決める。**表示してよいか(適用済み・購読中)は毎回判定し直し**、
 * 画面の復元で引き継ぐのは利用者の開閉の選択([savedExpanded])だけにする。
 *
 * @param clipboardHint 画面が前面でフォーカスを持つまで待ってからクリップボードの手がかりを返す
 */
internal suspend fun decideEntryPhase(
    hasAppliedLocally: () -> Boolean,
    isActiveSubscriber: suspend () -> Boolean,
    clipboardHint: suspend () -> Boolean,
    entryEnabled: suspend () -> Boolean,
    savedExpanded: Boolean?,
): EntryPhase {
    // 適用済み・購読中なら出さない(端末内の判定なので通信より先に見る)
    if (hasAppliedLocally() || isActiveSubscriber()) return EntryPhase.HIDDEN_LOCKED
    // 手がかりがあればサーバーを待たずに開く(利用者が閉じていたら畳んだまま)
    if (clipboardHint()) return if (savedExpanded == false) EntryPhase.COLLAPSED else EntryPhase.EXPANDED
    // 前回開いていた(入力の途中)なら開いたまま復元する
    if (savedExpanded == true) return EntryPhase.EXPANDED
    return if (entryEnabled()) EntryPhase.COLLAPSED else EntryPhase.HIDDEN
}

/**
 * 紹介コードの入口(段階的開示)。初回に着地する画面へ 1 行置く。
 *
 * | 状況 | 表示 |
 * |---|---|
 * | この端末で適用済み(Install Referrer による自動適用を含む) | 何も出さない |
 * | 有効な購読がある | 何も出さない |
 * | クリップボードに URL の手がかりがある | 入力欄を開いた状態 |
 * | 受付中のコードがある(サーバーの entryEnabled) | 小さな 1 行「紹介コードを入力する」。タップで開く |
 * | それ以外 | 何も出さない |
 *
 * 表示されたことを SDK に知らせ、保留中の Install Referrer のコードを適用させる。
 */
@Composable
public fun GoAlongReferralEntry(
    modifier: Modifier = Modifier,
    labels: Labels = Labels(),
    theme: GoAlongTheme = GoAlongTheme(),
    rcAppUserId: String? = null,
    onApplied: (GoAlongCreator?) -> Unit = {},
) {
    val l = labels.resolve()
    val t = theme.resolve()
    val context = LocalContext.current
    val windowInfo = LocalWindowInfo.current
    // 表示の可否は保存しない(復元のたびに判定し直す)。保存するのは開閉の選択だけ
    var phase by remember { mutableStateOf(EntryPhase.CHECKING) }
    var savedExpanded by rememberSaveable { mutableStateOf<Boolean?>(null) }
    fun open(expanded: Boolean) {
        savedExpanded = expanded
        phase = if (expanded) EntryPhase.EXPANDED else EntryPhase.COLLAPSED
    }

    LaunchedEffect(Unit) {
        // 保留中の Install Referrer を適用する(表示の判定は待たない)
        launch { GoAlong.onReferralEntryShown() }
        // Install Referrer で適用されたら入口は不要
        launch {
            GoAlong.events.collect { event ->
                if (event is GoAlongEvent.InstallReferrerApplied) phase = EntryPhase.HIDDEN_LOCKED
            }
        }
        val decided = decideEntryPhase(
            hasAppliedLocally = { GoAlong.hasAppliedReferralLocally },
            isActiveSubscriber = { GoAlong.isConfigured && GoAlong.isActiveSubscriber() },
            clipboardHint = {
                // クリップボードは前面でフォーカスを持ってから判定する
                snapshotFlow { windowInfo.isWindowFocused }.first { it }
                ClipboardHint.hasUrlHint(context)
            },
            entryEnabled = { GoAlong.referralEntryEnabled() },
            savedExpanded = savedExpanded,
        )
        if (phase == EntryPhase.CHECKING) phase = decided
        // 前面に戻るたびにクリップボードを見直す(LP でリンクをコピーして戻ってきた場合)
        snapshotFlow { windowInfo.isWindowFocused }.collect { focused ->
            if (focused && (phase == EntryPhase.COLLAPSED || phase == EntryPhase.HIDDEN) &&
                savedExpanded != false && !GoAlong.hasAppliedReferralLocally && ClipboardHint.hasUrlHint(context)
            ) {
                phase = EntryPhase.EXPANDED
            }
        }
    }

    Box(modifier.animateContentSize()) {
        when (phase) {
            EntryPhase.CHECKING, EntryPhase.HIDDEN, EntryPhase.HIDDEN_LOCKED -> Unit
            EntryPhase.COLLAPSED -> Row(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button) { open(true) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    l.collapsed,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = t.secondaryTextColor,
                )
                ChevronDownIcon(t.secondaryTextColor)
            }
            EntryPhase.EXPANDED -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(t.cardBackground, RoundedCornerShape(t.cardCornerRadius))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(l.entryTitle, style = t.titleStyle, color = t.textColor)
                    Spacer(Modifier.weight(1f))
                    // URL らしさだけで開くことがあるので、無関係だった人が畳めるようにする
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clickable { open(false) }
                            .semantics {
                                contentDescription = l.close
                                role = Role.Button
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier.size(28.dp).background(t.subtleFill, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { CloseIcon(t.secondaryTextColor) }
                    }
                }
                GoAlongCodeInput(
                    labels = labels,
                    theme = theme,
                    rcAppUserId = rcAppUserId,
                    showsTitle = false,
                    onApplied = onApplied,
                )
            }
        }
    }
}
