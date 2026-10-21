package me.goalong.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class InstallReferrerParserTest {
    @Test fun acceptsOnlyGoalongCode() {
        assertEquals("MIKA", InstallReferrerParser.parse("goalong_code=MIKA"))
        assertEquals("MIKA", InstallReferrerParser.parse("goalong_code=mika"))
        assertEquals("MIKA", InstallReferrerParser.parse(" goalong_code=MIKA "))
        assertEquals("MIKA", InstallReferrerParser.parse("goalong_code=%4D%49%4B%41"))
        assertEquals("MIKA", InstallReferrerParser.parse("goalong_code=%EF%BC%AD%EF%BC%A9%EF%BC%AB%EF%BC%A1"))
        assertEquals("ABCDEFGHIJKLMNOPQRST", InstallReferrerParser.parse("goalong_code=ABCDEFGHIJKLMNOPQRST"))
    }

    @Test fun rejectsEverythingElse() {
        for (value in listOf(
            null, "", "   ",
            // オーガニックなインストールの referrer
            "utm_source=google-play&utm_medium=organic",
            // 他のキーとの混在・重複キー
            "goalong_code=MIKA&utm_source=x",
            "utm_source=x&goalong_code=MIKA",
            "goalong_code=MIKA&goalong_code=SHO",
            "goalong_code=MIKA&goalong_code=MIKA",
            // キー違い・値なし
            "goalong_codes=MIKA", "GOALONG_CODE=MIKA", "code=MIKA", "goalong_code", "goalong_code=", "=MIKA",
            "goalong_code==MIKA", "goalong_code=MI=KA",
            // 二重エンコード・不正なエンコード
            "goalong_code%3DMIKA", "goalong_code=%4", "goalong_code=%ZZ", "goalong_code=%FF",
            "goalong_code=%C3%28", "goalong_code=%2520MIKA",
            // 形式不正
            "goalong_code=AB", "goalong_code=ABCDEFGHIJKLMNOPQRSTU", "goalong_code=MI-KA",
            "goalong_code=MI+KA", "goalong_code=日本語",
            // 長すぎる入力
            "goalong_code=MIKA" + " ".repeat(300) + "x",
            "goalong_code=" + "%41".repeat(70),
        )) {
            assertNull(value, InstallReferrerParser.parse(value))
        }
    }
}

/** Install Referrer の状態遷移(計画書 §2-3)。到着順のすべての組み合わせ */
class InstallReferrerStateTest {
    private fun referrer(value: String?) = FakeReferrerSource { ReferrerFetchResult.Success(value) }

    private fun applyCalls(h: Harness) = h.transport.calls("codes/apply")

    @Test fun noCodeOrUnavailableBecomesNone() = runTest {
        for (source in listOf(
            referrer(null),
            referrer("utm_source=google-play&utm_medium=organic"),
            referrer("goalong_code=AB"),
            FakeReferrerSource { ReferrerFetchResult.Unavailable },
        )) {
            val h = Harness(this, referrer = source)
            h.client().start()
            advanceUntilIdle()
            assertEquals(InstallReferrerState.NONE, h.client().installReferrerState)
            assertTrue(applyCalls(h).isEmpty())
        }
    }

    @Test fun transientFailureStaysUnreadAndRetriesOnNextLaunch() = runTest {
        val source = FakeReferrerSource { ReferrerFetchResult.RetryLater }
        val h = Harness(this, referrer = source)
        h.client().start()
        advanceUntilIdle()
        assertEquals(InstallReferrerState.UNREAD, h.client().installReferrerState)
        source.result = { throw IllegalStateException("binder died") }
        h.client().start()
        advanceUntilIdle()
        assertEquals(InstallReferrerState.UNREAD, h.client().installReferrerState)
        source.result = { ReferrerFetchResult.Success("goalong_code=MIKA") }
        h.client().start()
        advanceUntilIdle()
        assertEquals(InstallReferrerState.PENDING, h.client().installReferrerState)
        assertEquals("MIKA", h.store.values()[StorageKeys.INSTALL_REFERRER_CODE])
        assertEquals(3, source.fetchCount)
        // 読めた後は二度と読まない
        h.client().start()
        advanceUntilIdle()
        assertEquals(3, source.fetchCount)
    }

    @Test fun readsOnlyOncePerProcess() = runTest {
        val gate = CompletableDeferred<ReferrerFetchResult>()
        val source = FakeReferrerSource { gate.await() }
        val h = Harness(this, referrer = source)
        val client = h.client()
        client.start()
        client.startInstallReferrerIfNeeded()
        client.startInstallReferrerIfNeeded()
        runCurrent()
        gate.complete(ReferrerFetchResult.RetryLater)
        advanceUntilIdle()
        assertEquals(1, source.fetchCount)
    }

    @Test fun pendingWithoutTriggerDoesNotApply() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        h.client().start()
        advanceUntilIdle()
        assertEquals(InstallReferrerState.PENDING, h.client().installReferrerState)
        assertTrue(applyCalls(h).isEmpty())
    }

    private fun assertAppliedViaReferrer(h: Harness, rcAppUserId: String?) {
        assertEquals(1, applyCalls(h).size)
        val body = h.transport.body("codes/apply")
        assertEquals("INSTALL_REFERRER", body.getString("method"))
        assertEquals("MIKA", body.getString("code"))
        assertEquals(false, body.getBoolean("confirmReplace"))
        assertEquals("android", body.getString("platform"))
        if (rcAppUserId != null) assertEquals(rcAppUserId, body.getString("rcAppUserId"))
        val client = h.client()
        assertEquals(InstallReferrerState.APPLIED, client.installReferrerState)
        assertTrue(client.hasAppliedReferral)
        assertTrue(h.events.any { it is GoAlongEvent.InstallReferrerApplied && it.code == "MIKA" })
    }

    @Test fun fetchThenRevenueCatId() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        val client = h.client()
        client.start()
        advanceUntilIdle()
        client.setRevenueCatUserId("rc-1")
        assertAppliedViaReferrer(h, "rc-1")
        // 適用後に特典の claim も行う
        assertEquals(1, h.transport.calls("perks/claim").size)
    }

    @Test fun revenueCatIdThenFetch() = runTest {
        val gate = CompletableDeferred<ReferrerFetchResult>()
        val h = Harness(this, referrer = FakeReferrerSource { gate.await() })
        val client = h.client()
        client.start()
        runCurrent()
        client.setRevenueCatUserId("rc-1")
        assertTrue(applyCalls(h).isEmpty())
        gate.complete(ReferrerFetchResult.Success("goalong_code=MIKA"))
        advanceUntilIdle()
        assertAppliedViaReferrer(h, "rc-1")
    }

    @Test fun revenueCatIdFromPreviousLaunchThenFetch() = runTest {
        val h = Harness(
            this,
            store = InMemoryStore(mapOf(StorageKeys.INSTALLATION_REPORTED to true, StorageKeys.RC_APP_USER_ID to "rc-old")),
            referrer = referrer("goalong_code=MIKA"),
        )
        h.client().start()
        advanceUntilIdle()
        assertAppliedViaReferrer(h, "rc-old")
    }

    @Test fun entryShownThenFetch() = runTest {
        val gate = CompletableDeferred<ReferrerFetchResult>()
        val h = Harness(this, referrer = FakeReferrerSource { gate.await() })
        val client = h.client()
        client.start()
        runCurrent()
        client.onReferralEntryShown()
        assertTrue(applyCalls(h).isEmpty())
        gate.complete(ReferrerFetchResult.Success("goalong_code=MIKA"))
        advanceUntilIdle()
        assertAppliedViaReferrer(h, null)
        assertTrue(!h.transport.body("codes/apply").has("rcAppUserId"))
    }

    @Test fun fetchThenEntryShown() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        val client = h.client()
        client.start()
        advanceUntilIdle()
        client.onReferralEntryShown()
        assertAppliedViaReferrer(h, null)
    }

    @Test fun pendingSurvivesRestartAndAppliesOnNextTrigger() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        h.client().start()
        advanceUntilIdle()
        // 次回起動: 既に pending なので読み直さず、入口の表示で適用する
        val next = h.client()
        next.start()
        advanceUntilIdle()
        assertEquals(1, h.referrer!!.fetchCount)
        next.onReferralEntryShown()
        assertAppliedViaReferrer(h, null)
    }

    @Test fun simultaneousTriggersApplyOnce() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        val client = h.client()
        client.start()
        advanceUntilIdle()
        val a = launch { client.onReferralEntryShown() }
        val b = launch { client.setRevenueCatUserId("rc-1") }
        val c = launch { client.tryApplyPendingReferrer() }
        a.join(); b.join(); c.join()
        assertEquals(1, applyCalls(h).size)
        assertEquals(InstallReferrerState.APPLIED, client.installReferrerState)
    }

    @Test fun networkFailureStaysPendingAndRetriesAtNextOpportunity() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        val client = h.client()
        client.start()
        advanceUntilIdle()
        h.transport.handler = { throw IOException("offline") }
        client.onReferralEntryShown()
        assertEquals(InstallReferrerState.PENDING, client.installReferrerState)
        h.transport.handler = { HttpResponse(503, "{}") }
        client.setRevenueCatUserId("rc-1")
        assertEquals(InstallReferrerState.PENDING, client.installReferrerState)
        h.transport.handler = { FakeTransport.defaultResponse(it) }
        client.onReferralEntryShown()
        assertEquals(InstallReferrerState.APPLIED, client.installReferrerState)
        assertEquals(3, applyCalls(h).size)
        assertEquals(1, h.events.count { it is GoAlongEvent.InstallReferrerApplied })
    }

    @Test fun alreadyAppliedOnServerCountsAsApplied() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        val client = h.client()
        client.start()
        advanceUntilIdle()
        h.transport.handler = { jsonResponse("status" to "already_applied") }
        client.onReferralEntryShown()
        assertEquals(InstallReferrerState.APPLIED, client.installReferrerState)
    }

    @Test fun manualReferralFirstMakesReferrerIneligibleWithoutRequest() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        val client = h.client()
        client.start()
        advanceUntilIdle()
        client.applyCode("SHO")
        client.onReferralEntryShown()
        assertEquals(1, applyCalls(h).size)
        assertEquals("SHO", h.transport.body("codes/apply").getString("code"))
        assertEquals(InstallReferrerState.INELIGIBLE, client.installReferrerState)
        assertTrue(
            h.events.contains(
                GoAlongEvent.InstallReferrerIneligible("MIKA", GoAlongEvent.InstallReferrerIneligible.Reason.ALREADY_REFERRED),
            ),
        )
    }

    @Test fun permanentFailuresBecomeIneligible() = runTest {
        val cases = listOf(
            jsonResponse("status" to "conflict") to GoAlongEvent.InstallReferrerIneligible.Reason.ALREADY_REFERRED,
            jsonResponse("status" to "existing_subscriber") to GoAlongEvent.InstallReferrerIneligible.Reason.EXISTING_SUBSCRIBER,
            jsonResponse("status" to "expired") to GoAlongEvent.InstallReferrerIneligible.Reason.EXPIRED,
            jsonResponse("status" to "not_found") to GoAlongEvent.InstallReferrerIneligible.Reason.NOT_FOUND,
        )
        for ((response, reason) in cases) {
            val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
            val client = h.client()
            client.start()
            advanceUntilIdle()
            h.transport.handler = { response }
            client.onReferralEntryShown()
            assertEquals(InstallReferrerState.INELIGIBLE, client.installReferrerState)
            assertEquals(listOf<GoAlongEvent>(GoAlongEvent.InstallReferrerIneligible("MIKA", reason)), h.events.toList())
            // 以後の機会では再試行しない
            client.setRevenueCatUserId("rc")
            assertEquals(1, applyCalls(h).size)
        }
    }

    @Test fun activeSubscriberIsCheckedBeforeApplying() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"), subscriber = { true })
        val client = h.client()
        client.start()
        advanceUntilIdle()
        client.onReferralEntryShown()
        assertTrue(applyCalls(h).isEmpty())
        assertEquals(InstallReferrerState.INELIGIBLE, client.installReferrerState)
    }

    @Test fun neverReappliesAfterRemovalOfAppliedReferral() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        val client = h.client()
        client.start()
        advanceUntilIdle()
        client.onReferralEntryShown()
        assertEquals(InstallReferrerState.APPLIED, client.installReferrerState)
        assertTrue(client.removeReferral())
        assertTrue(!client.hasAppliedReferral)
        client.onReferralEntryShown()
        client.setRevenueCatUserId("rc")
        val restarted = h.client()
        restarted.start()
        advanceUntilIdle()
        restarted.onReferralEntryShown()
        assertEquals(1, applyCalls(h).size)
        assertEquals(InstallReferrerState.APPLIED, restarted.installReferrerState)
    }

    @Test fun removalWhilePendingCancelsAutoApply() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        val client = h.client()
        client.start()
        advanceUntilIdle()
        h.transport.handler = { jsonResponse("status" to "no_attribution") }
        client.removeReferral()
        assertEquals(InstallReferrerState.INELIGIBLE, client.installReferrerState)
        h.transport.handler = { FakeTransport.defaultResponse(it) }
        client.onReferralEntryShown()
        assertTrue(applyCalls(h).isEmpty())
    }

    @Test fun serverReportsRemovedWhilePending() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        val client = h.client()
        client.start()
        advanceUntilIdle()
        h.transport.handler = { jsonResponse("status" to "removed") }
        client.currentReferral()
        assertEquals(InstallReferrerState.INELIGIBLE, client.installReferrerState)
        h.transport.handler = { FakeTransport.defaultResponse(it) }
        client.onReferralEntryShown()
        assertTrue(applyCalls(h).isEmpty())
    }

    @Test fun referrerApplyIsSerializedWithManualApply() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        val client = h.client()
        client.start()
        advanceUntilIdle()
        val gate = CompletableDeferred<HttpResponse>()
        h.transport.handler = { gate.await() }
        val manual = async { client.applyCode("SHO") }
        runCurrent()
        val auto = async { client.onReferralEntryShown() }
        runCurrent()
        assertEquals(1, applyCalls(h).size)
        gate.complete(jsonResponse("status" to "applied"))
        manual.await()
        auto.await()
        // 手入力の適用が先に完了したので、referrer は上書きしない
        assertEquals(1, applyCalls(h).size)
        assertEquals(InstallReferrerState.INELIGIBLE, client.installReferrerState)
    }
}
