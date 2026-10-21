package me.goalong.sdk.compose

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.goalong.sdk.ApplyMethod
import me.goalong.sdk.ApplyResult
import me.goalong.sdk.CodeErrorKind
import me.goalong.sdk.CodeValidation
import me.goalong.sdk.GoAlongCreator
import me.goalong.sdk.GoAlongException
import me.goalong.sdk.GoAlongPerk
import me.goalong.sdk.GoAlongReferralSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 偽の SDK。応答は関数で差し替え、呼び出しを記録する */
internal class FakeApi : GoAlongCodeApi {
    data class ApplyCall(val code: String, val method: ApplyMethod, val rcAppUserId: String?, val confirmReplace: Boolean)

    val validateCalls = mutableListOf<String>()
    val applyCalls = mutableListOf<ApplyCall>()
    var currentCalls = 0
    var rcIdStored = false
    var local: GoAlongReferralSnapshot? = null

    var onValidate: suspend (String) -> CodeValidation = { CodeValidation(true, null, it, null, null) }
    var onApply: suspend (ApplyCall) -> ApplyResult = { ApplyResult.Applied(null) }
    var onCurrent: suspend () -> GoAlongReferralSnapshot = { GoAlongReferralSnapshot.None }

    override suspend fun validateCode(code: String): CodeValidation {
        validateCalls += code
        return onValidate(code)
    }

    override suspend fun applyCode(code: String, method: ApplyMethod, rcAppUserId: String?, confirmReplace: Boolean): ApplyResult {
        val call = ApplyCall(code, method, rcAppUserId, confirmReplace)
        applyCalls += call
        return onApply(call)
    }

    override suspend fun currentReferral(): GoAlongReferralSnapshot {
        currentCalls++
        return onCurrent()
    }

    override fun extractCode(text: String): String? =
        Regex("^https://goalong\\.me/booknotion/([A-Za-z0-9]{3,20})$").matchEntire(text.trim())
            ?.groupValues?.get(1)?.uppercase()

    override fun hasRevenueCatUserId() = rcIdStored
    override fun localAppliedSnapshot() = local
}

private val perk = GoAlongPerk("無料", "pro", 4_000_000_000_000L)

class GoAlongCodeControllerTest {
    private fun TestScope.controller(
        api: FakeApi,
        code: String = "",
        rcAppUserId: String? = null,
    ) = GoAlongCodeController(this, rcAppUserId, api, code, ApplyMethod.CODE)

    @Test fun debouncesFor400msAfterThreeCharacters() = runTest {
        val api = FakeApi()
        val c = controller(api)
        c.onCodeChange("MI")
        advanceTimeBy(1_000)
        assertTrue("2 文字では検証しない", api.validateCalls.isEmpty())
        c.onCodeChange("MIK")
        advanceTimeBy(399)
        runCurrent()
        assertEquals(GoAlongCodePhase.Idle, c.phase)
        c.onCodeChange("MIKA")
        advanceTimeBy(399)
        runCurrent()
        assertTrue("入力のたびに待ち直す", api.validateCalls.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("MIKA"), api.validateCalls)
        assertEquals(GoAlongCodePhase.Valid(null), c.phase)
        assertTrue(c.state.value.needsConfirmation)
        assertTrue("手入力は自動で適用しない", api.applyCalls.isEmpty())
        c.dispose()
    }

    @Test fun discardsStaleValidationWhileTyping() = runTest {
        val api = FakeApi()
        val response = CompletableDeferred<CodeValidation>()
        api.onValidate = { response.await() }
        val c = controller(api, "MIKA")
        val checking = async { c.validatePreview() }
        runCurrent()
        assertEquals(GoAlongCodePhase.Checking, c.phase)
        c.onCodeChange("SHO")
        response.complete(CodeValidation(true, null, "MIKA", GoAlongCreator("MIKA", null), perk))
        checking.await()
        assertEquals(GoAlongCodePhase.Idle, c.phase)
        assertNull(c.state.value.creator)
        assertEquals("SHO", c.state.value.code)
        c.dispose()
    }

    @Test fun previewCompletedDuringApplyDoesNotOverwriteDone() = runTest {
        val api = FakeApi()
        val response = CompletableDeferred<CodeValidation>()
        api.onValidate = { response.await() }
        api.onApply = { ApplyResult.Applied(GoAlongCreator("MIKA", null)) }
        val c = controller(api, "MIKA")
        val preview = async { c.validatePreview() }
        runCurrent()
        c.apply()
        response.complete(CodeValidation(false, CodeErrorKind.EXPIRED, null, null, null))
        preview.await()
        assertEquals(GoAlongCodePhase.Done(null), c.phase)
        assertEquals("MIKA", c.state.value.creator?.displayName)
    }

    @Test fun pastedLinkShowsOnlyCodeAndAutoAppliesAsClipboard() = runTest {
        val api = FakeApi()
        val c = controller(api)
        c.onCodeChange("https://goalong.me/booknotion/mika")
        assertEquals("MIKA", c.state.value.code)
        assertEquals(ApplyMethod.CLIPBOARD, c.state.value.method)
        assertTrue(api.applyCalls.isEmpty())
        advanceUntilIdle()
        assertEquals(GoAlongCodePhase.Done(null), c.phase)
        assertEquals(ApplyMethod.CLIPBOARD, api.applyCalls.single().method)
        assertFalse(c.state.value.needsConfirmation)
    }

    @Test fun unrecognizedLinkFailsWithFormatErrorWithoutRequest() = runTest {
        val api = FakeApi()
        val c = controller(api)
        c.onCodeChange("https://example.com/private/SECRET")
        advanceUntilIdle()
        assertEquals(GoAlongCodePhase.Failed(GoAlongCodeIssue.INVALID_FORMAT), c.phase)
        assertTrue(api.validateCalls.isEmpty())
        assertTrue(api.applyCalls.isEmpty())
    }

    @Test fun pastingLinkOverSameCodePreviewSwitchesToClipboard() = runTest {
        val api = FakeApi()
        val c = controller(api, "MIKA")
        c.validatePreview()
        assertEquals(GoAlongCodePhase.Valid(null), c.phase)
        c.onCodeChange("https://goalong.me/booknotion/MIKA")
        advanceUntilIdle()
        assertEquals(GoAlongCodePhase.Done(null), c.phase)
        assertEquals(1, api.applyCalls.size)
        assertEquals(ApplyMethod.CLIPBOARD, api.applyCalls.single().method)
    }

    @Test fun conflictReplaceDoneAndReentryAfterRemoval() = runTest {
        val api = FakeApi()
        val c = controller(api, "MIKA")
        api.onApply = { ApplyResult.Conflict(GoAlongCreator("SHO", null)) }
        c.apply()
        assertEquals(GoAlongCodePhase.Failed(GoAlongCodeIssue.CONFLICT), c.phase)
        api.onApply = { ApplyResult.Applied(null) }
        c.apply(confirmReplace = true)
        assertTrue(api.applyCalls.last().confirmReplace)
        assertEquals(GoAlongCodePhase.Done(null), c.phase)
        // API で取り消した後、画面を開き直すと取消済みを表示し、再入力できる
        api.onCurrent = { GoAlongReferralSnapshot.Removed(null) }
        c.reset()
        c.loadCurrentReferral()
        assertEquals(GoAlongReferralSnapshot.Removed(null), c.state.value.current)
        c.onCodeChange("SHO")
        assertEquals(GoAlongCodePhase.Idle, c.phase)
    }

    @Test fun preventsDoubleSubmitAndIgnoresResultsAfterDispose() = runTest {
        val api = FakeApi()
        val response = CompletableDeferred<ApplyResult>()
        api.onApply = { response.await() }
        val c = controller(api, "MIKA")
        val applying = async { c.apply() }
        runCurrent()
        assertNull(c.apply())
        c.onCodeChange("SHO")
        assertEquals("適用中は編集を受け付けない", "MIKA", c.state.value.code)
        c.dispose()
        val before = c.state.value
        response.complete(ApplyResult.Applied(null))
        assertNull(applying.await())
        assertEquals(before, c.state.value)
        assertEquals(1, api.applyCalls.size)
    }

    @Test fun networkFailureKeepsInputAndCanRetry() = runTest {
        val api = FakeApi()
        val c = controller(api, "MIKA")
        api.onApply = { throw GoAlongException.Network() }
        c.apply()
        assertEquals(GoAlongCodePhase.Failed(GoAlongCodeIssue.NETWORK), c.phase)
        assertEquals("MIKA", c.state.value.code)
        api.onApply = { ApplyResult.Applied(null) }
        c.retry()
        assertEquals(GoAlongCodePhase.Done(null), c.phase)
    }

    @Test fun validationNetworkFailureRetriesValidation() = runTest {
        val api = FakeApi()
        val c = controller(api, "MIKA")
        api.onValidate = { throw GoAlongException.Server(503) }
        c.validatePreview()
        assertEquals(GoAlongCodePhase.Failed(GoAlongCodeIssue.NETWORK), c.phase)
        api.onValidate = { CodeValidation(true, null, it, null, perk) }
        c.retry()
        assertEquals(GoAlongCodePhase.Valid(perk), c.phase)
        assertTrue(api.applyCalls.isEmpty())
    }

    @Test fun replaceRetryKeepsConfirmReplaceAndClipboard() = runTest {
        val api = FakeApi()
        val c = controller(api)
        api.onApply = { ApplyResult.Conflict(null) }
        c.onCodeChange("https://goalong.me/booknotion/MIKA")
        advanceUntilIdle()
        assertEquals(GoAlongCodePhase.Failed(GoAlongCodeIssue.CONFLICT), c.phase)
        api.onApply = { throw GoAlongException.Network() }
        c.apply(confirmReplace = true)
        assertEquals(GoAlongCodePhase.Failed(GoAlongCodeIssue.NETWORK), c.phase)
        api.onApply = { ApplyResult.Applied(null) }
        c.retry()
        assertEquals(GoAlongCodePhase.Done(null), c.phase)
        assertTrue(api.applyCalls.last().confirmReplace)
        assertEquals(ApplyMethod.CLIPBOARD, api.applyCalls.last().method)
    }

    @Test fun headlessDebounceDropsStalePerkAndClearsPreviewOnEdit() = runTest {
        val api = FakeApi()
        val old = CompletableDeferred<CodeValidation>()
        api.onValidate = { old.await() }
        val c = controller(api)
        c.onCodeChange("MIKA")
        advanceTimeBy(400)
        runCurrent()
        assertEquals(GoAlongCodePhase.Checking, c.phase)
        c.onCodeChange("SHO")
        old.complete(CodeValidation(true, null, "MIKA", null, GoAlongPerk("古い特典")))
        runCurrent()
        assertEquals(GoAlongCodePhase.Idle, c.phase)
        api.onValidate = { CodeValidation(true, null, it, null, GoAlongPerk("新しい特典")) }
        advanceTimeBy(400)
        runCurrent()
        assertEquals(GoAlongCodePhase.Valid(GoAlongPerk("新しい特典")), c.phase)
        assertTrue(api.applyCalls.isEmpty())
        c.onCodeChange("AB")
        assertEquals(GoAlongCodePhase.Idle, c.phase)
        advanceTimeBy(1_000)
        assertEquals(2, api.validateCalls.size)
    }

    @Test fun linkPastedWhileLoadingCurrentIsValidatedAfterLoad() = runTest {
        val api = FakeApi()
        val current = CompletableDeferred<GoAlongReferralSnapshot>()
        api.onCurrent = { current.await() }
        val c = controller(api)
        val loading = async { c.loadCurrentReferral() }
        runCurrent()
        c.onCodeChange("https://goalong.me/booknotion/MIKA")
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(api.validateCalls.isEmpty())
        current.complete(GoAlongReferralSnapshot.None)
        loading.await()
        advanceUntilIdle()
        assertEquals(GoAlongCodePhase.Done(null), c.phase)
        assertEquals(ApplyMethod.CLIPBOARD, api.applyCalls.single().method)
    }

    @Test fun currentFailureIsSilent() = runTest {
        val api = FakeApi()
        api.onCurrent = { throw GoAlongException.Network() }
        val c = controller(api)
        c.loadCurrentReferral()
        assertEquals(GoAlongCodePhase.Idle, c.phase)
        assertNull(c.state.value.current)
        assertFalse(c.state.value.isLoadingCurrent)
    }

    @Test fun localAppliedStateIsShownBeforeServerResponds() = runTest {
        val api = FakeApi()
        api.local = GoAlongReferralSnapshot.Applied(null, null, perk)
        val server = CompletableDeferred<GoAlongReferralSnapshot>()
        api.onCurrent = { server.await() }
        val c = controller(api)
        val loading = async { c.loadCurrentReferral() }
        runCurrent()
        assertEquals(GoAlongReferralSnapshot.Applied(null, null, perk), c.state.value.current)
        server.complete(GoAlongReferralSnapshot.Applied(null, 1L, null))
        loading.await()
        assertEquals(GoAlongReferralSnapshot.Applied(null, 1L, null), c.state.value.current)
    }

    @Test fun perkPendingOnlyWhenPerkExistsUngrantedAndNoRcId() = runTest {
        for (idSource in listOf("none", "stored", "explicit")) {
            val api = FakeApi()
            api.rcIdStored = idSource == "stored"
            api.onValidate = { CodeValidation(true, null, it, null, GoAlongPerk("無料")) }
            api.onApply = { ApplyResult.Applied(null, perk = null, perkLabel = "無料") }
            val c = controller(api, "MIKA", rcAppUserId = if (idSource == "explicit") "explicit" else null)
            c.validatePreview()
            assertEquals(GoAlongCodePhase.Valid(GoAlongPerk("無料")), c.phase)
            c.apply()
            assertEquals(GoAlongCodePhase.Done(null), c.phase)
            assertEquals(idSource, idSource == "none", c.state.value.perkPending)
            if (idSource == "explicit") assertEquals("explicit", api.applyCalls.last().rcAppUserId)
            c.dispose()
        }
    }

    @Test fun alreadyAppliedWithPerkLabelIsPending() = runTest {
        val api = FakeApi()
        api.onApply = { ApplyResult.AlreadyApplied(null, perkLabel = "無料") }
        val c = controller(api, "MIKA")
        c.apply()
        assertEquals(GoAlongCodePhase.Done(null), c.phase)
        assertTrue(c.state.value.perkPending)
    }

    @Test fun noPerkMeansNotPending() = runTest {
        for (result in listOf<ApplyResult>(ApplyResult.AlreadyApplied(null), ApplyResult.Applied(null))) {
            val api = FakeApi()
            api.onApply = { result }
            val c = controller(api, "MIKA")
            c.apply()
            assertEquals(GoAlongCodePhase.Done(null), c.phase)
            assertFalse(c.state.value.perkPending)
        }
    }

    @Test fun grantedPerkIsReadableFromDone() = runTest {
        val api = FakeApi()
        api.onApply = { ApplyResult.Applied(null, perk = perk) }
        val c = controller(api, "MIKA")
        c.apply()
        assertEquals(GoAlongCodePhase.Done(perk), c.phase)
        assertFalse(c.state.value.perkPending)
    }

    @Test fun mapsFailureKinds() = runTest {
        val cases = mapOf<ApplyResult, GoAlongCodeIssue>(
            ApplyResult.ExistingSubscriber to GoAlongCodeIssue.EXISTING_SUBSCRIBER,
            ApplyResult.NotFound(CodeErrorKind.EXPIRED) to GoAlongCodeIssue.EXPIRED,
            ApplyResult.NotFound(CodeErrorKind.INVALID_FORMAT) to GoAlongCodeIssue.INVALID_FORMAT,
            ApplyResult.NotFound(CodeErrorKind.NOT_FOUND) to GoAlongCodeIssue.NOT_FOUND,
            ApplyResult.NotFound(null) to GoAlongCodeIssue.NOT_FOUND,
        )
        for ((result, issue) in cases) {
            val api = FakeApi()
            api.onApply = { result }
            val c = controller(api, "MIKA")
            c.apply()
            assertEquals(GoAlongCodePhase.Failed(issue), c.phase)
        }
        for ((error, issue) in mapOf(
            CodeErrorKind.EXPIRED to GoAlongCodeIssue.EXPIRED,
            CodeErrorKind.NOT_FOUND to GoAlongCodeIssue.NOT_FOUND,
            CodeErrorKind.INVALID_FORMAT to GoAlongCodeIssue.INVALID_FORMAT,
        )) {
            val api = FakeApi()
            api.onValidate = { CodeValidation(false, error, null, null, null) }
            val c = controller(api, "MIKA")
            c.validatePreview()
            assertEquals(GoAlongCodePhase.Failed(issue), c.phase)
        }
    }

    @Test fun disposedLinkValidationDoesNotStartApply() = runTest {
        val api = FakeApi()
        val response = CompletableDeferred<CodeValidation>()
        api.onValidate = { response.await() }
        val c = controller(api)
        c.onCodeChange("https://goalong.me/booknotion/MIKA")
        val preview = async { c.validatePreview() }
        runCurrent()
        c.dispose()
        response.complete(CodeValidation(true, null, "MIKA", null, null))
        preview.await()
        advanceUntilIdle()
        assertTrue(api.applyCalls.isEmpty())
    }

    @Test fun doneIgnoresFurtherEdits() = runTest {
        val api = FakeApi()
        val c = controller(api, "MIKA")
        c.apply()
        c.onCodeChange("SHO")
        assertEquals("MIKA", c.state.value.code)
        assertNull(c.apply())
        assertEquals(1, api.applyCalls.size)
    }
}

class ClipboardHintTest {
    @Test fun usesUrlScoreOnlyWhenClassificationCompleteOnApi31Plus() {
        assertFalse(ClipboardHint.decide(30, true) { 1f })
        assertFalse(ClipboardHint.decide(31, false) { 1f })
        assertFalse(ClipboardHint.decide(34, true) { 0.2f })
        assertTrue(ClipboardHint.decide(31, true) { 0.9f })
        assertFalse(ClipboardHint.decide(33, true) { throw IllegalStateException("not classified") })
    }
}
