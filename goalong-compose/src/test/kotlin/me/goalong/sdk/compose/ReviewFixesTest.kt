package me.goalong.sdk.compose

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.goalong.sdk.ApplyMethod
import me.goalong.sdk.ApplyResult
import me.goalong.sdk.CodeValidation
import me.goalong.sdk.GoAlongReferralSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 公開前レビュー(Codex)の指摘 6・7 の再現テスト */
class ReviewFixesTest {
    private fun TestScope.controller(api: FakeApi, code: String = "MIKA") =
        GoAlongCodeController(this, null, api, code, ApplyMethod.CODE)

    // 6【中】取り消されても Applying のまま残らない

    @Test fun cancelledApplyReleasesApplyingAndRefreshesCurrent() = runTest {
        val api = FakeApi()
        val never = CompletableDeferred<ApplyResult>()
        api.onApply = { never.await() }
        val c = controller(api)
        val job = launch { c.apply() }
        runCurrent()
        assertEquals(GoAlongCodePhase.Applying, c.phase)
        job.cancel()
        runCurrent()
        assertEquals(GoAlongCodePhase.Idle, c.phase)
        // 編集・reset を受け付ける
        c.onCodeChange("SHO")
        assertEquals("SHO", c.state.value.code)
        c.reset()
        assertEquals("", c.state.value.code)
        // サーバー側で適用済みになっていた分は current の再取得で分かる
        api.onCurrent = { GoAlongReferralSnapshot.Applied(null, 1L, null) }
        c.loadCurrentReferral()
        assertEquals(GoAlongReferralSnapshot.Applied(null, 1L, null), c.state.value.current)
        // 再適用もできる
        api.onApply = { ApplyResult.Applied(null) }
        c.onCodeChange("MIKA")
        c.apply()
        assertEquals(GoAlongCodePhase.Done(null), c.phase)
        c.dispose()
    }

    @Test fun cancelledValidationReleasesChecking() = runTest {
        val api = FakeApi()
        val never = CompletableDeferred<CodeValidation>()
        api.onValidate = { never.await() }
        val c = controller(api)
        val job = launch { c.validatePreview() }
        runCurrent()
        assertEquals(GoAlongCodePhase.Checking, c.phase)
        job.cancel()
        runCurrent()
        assertEquals(GoAlongCodePhase.Idle, c.phase)
        assertFalse(c.state.value.isBusy)
        c.dispose()
    }

    // 7【中】開閉の状態を復元しても、適用済み・購読中の判定を飛ばさない

    private suspend fun decide(
        applied: Boolean = false,
        subscriber: Boolean = false,
        hint: Boolean = false,
        enabled: Boolean = false,
        saved: Boolean? = null,
    ): EntryPhase = decideEntryPhase({ applied }, { subscriber }, { hint }, { enabled }, saved)

    @Test fun restoredOpenStateNeverSkipsEligibilityChecks() = runTest {
        for (saved in listOf(true, false, null)) {
            assertEquals(EntryPhase.HIDDEN_LOCKED, decide(applied = true, hint = true, enabled = true, saved = saved))
            assertEquals(EntryPhase.HIDDEN_LOCKED, decide(subscriber = true, hint = true, enabled = true, saved = saved))
        }
    }

    @Test fun entryDecisionTable() = runTest {
        assertEquals(EntryPhase.EXPANDED, decide(hint = true))
        assertEquals(EntryPhase.COLLAPSED, decide(hint = true, saved = false))
        assertEquals(EntryPhase.EXPANDED, decide(saved = true))
        assertEquals(EntryPhase.COLLAPSED, decide(enabled = true))
        assertEquals(EntryPhase.COLLAPSED, decide(enabled = true, saved = false))
        // 手がかりなし・受付なしなら何も出さない
        assertEquals(EntryPhase.HIDDEN, decide())
        assertEquals(EntryPhase.HIDDEN, decide(saved = false))
    }

    @Test fun hintSkipsServerRequest() = runTest {
        var asked = false
        val phase = decideEntryPhase({ false }, { false }, { true }, { asked = true; true }, null)
        assertEquals(EntryPhase.EXPANDED, phase)
        assertTrue(!asked)
    }
}
