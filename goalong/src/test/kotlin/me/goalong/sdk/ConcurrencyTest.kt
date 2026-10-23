package me.goalong.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS の ReferralConcurrencyTests を移植: 状態を変える操作は通信開始から保存完了まで直列 */
class ConcurrencyTest {
    private enum class Op(val path: String) {
        CURRENT("codes/current"), APPLY("codes/apply"), REMOVE("codes/remove"), CLAIM("perks/claim");

        suspend fun perform(client: GoAlongClient) {
            when (this) {
                CURRENT -> client.currentReferral()
                APPLY -> client.applyCode("MIKA", confirmReplace = true)
                REMOVE -> client.removeReferral()
                CLAIM -> client.setRevenueCatUserId("rc-user")
            }
        }
    }

    private val newReferral = mapOf(
        "status" to "applied", "perk" to perkJson, "offeringKey" to "new-offering", "perkLabel" to "new-label",
    )

    private fun seed(h: Harness) {
        h.store.edit {
            put(StorageKeys.REFERRAL_APPLIED, true)
            put(StorageKeys.REFERRAL_OFFERING_KEY, "old-offering")
            put(StorageKeys.REFERRAL_PERK_LABEL, "old-label")
            put(StorageKeys.APPLIED_PERK_LABEL, "old-perk")
        }
    }

    /**
     * 1 つ目の応答を保留し、その間に 2 つ目の通信が始まらないことを確かめる。
     * 直列化されていなければ 2 つ目が先に完了し、古い応答で上書きされる
     */
    private suspend fun TestScope.runInOrder(
        h: Harness,
        first: Op, firstResponse: Map<String, Any?>, firstStatus: Int = 200,
        second: Op, secondResponse: Map<String, Any?>,
    ) {
        val client = h.client()
        val pending = CompletableDeferred<HttpResponse>()
        var count = 0
        h.transport.handler = { request ->
            count++
            if (count == 1) {
                assertEquals("/api/sdk/v1/${first.path}", request.path)
                pending.await()
            } else {
                assertEquals("/api/sdk/v1/${second.path}", request.path)
                jsonResponse(secondResponse)
            }
        }
        val firstTask = async { runCatching { first.perform(client) } }
        runCurrent()
        assertEquals(1, count)
        val secondTask = async { second.perform(client) }
        runCurrent()
        assertEquals("2 つ目は 1 つ目の完了を待つ", 1, count)
        pending.complete(jsonResponse(firstResponse, firstStatus))
        val firstResult = firstTask.await()
        if (firstStatus != 200) {
            assertTrue(firstResult.exceptionOrNull() is GoAlongException.Server)
        } else {
            firstResult.getOrThrow()
        }
        secondTask.await()
        assertEquals(2, count)
    }

    private fun assertNewReferral(client: GoAlongClient) {
        assertTrue(client.hasAppliedReferral)
        assertEquals("new-offering", client.referralOfferingKey)
        assertEquals("new-label", client.referralPerkLabel)
        assertEquals(perk, client.cachedAppliedPerk)
        assertEquals("true", client.attributionAttributes()["goalong_referred"])
        assertEquals("new-offering", client.attributionAttributes()["goalong_offering"])
    }

    private fun assertCleared(client: GoAlongClient) {
        assertFalse(client.hasAppliedReferral)
        assertNull(client.referralOfferingKey)
        assertNull(client.referralPerkLabel)
        assertNull(client.cachedAppliedPerk)
        assertNull(client.attributionAttributes()["goalong_referred"])
    }

    @Test fun delayedCurrentNoneCannotClearSuccessfulApply() = runTest {
        val h = Harness(this)
        runInOrder(h, Op.CURRENT, mapOf("status" to "none"), second = Op.APPLY, secondResponse = newReferral)
        assertNewReferral(h.client())
    }

    @Test fun delayedCurrentAppliedCannotRestoreRemovedReferral() = runTest {
        val h = Harness(this)
        seed(h)
        runInOrder(
            h, Op.CURRENT, mapOf("status" to "applied", "perk" to perkJson),
            second = Op.REMOVE, secondResponse = mapOf("status" to "removed"),
        )
        assertCleared(h.client())
    }

    @Test fun delayedClaimCannotRestoreRemovedOffering() = runTest {
        val h = Harness(this)
        seed(h)
        runInOrder(
            h, Op.CLAIM,
            mapOf("status" to "granted", "perk" to perkJson, "offeringKey" to "claimed", "perkLabel" to "claimed-label"),
            second = Op.REMOVE, secondResponse = mapOf("status" to "no_attribution"),
        )
        assertCleared(h.client())
    }

    @Test fun delayedRemoveCannotClearReappliedReferral() = runTest {
        val h = Harness(this)
        seed(h)
        runInOrder(
            h, Op.REMOVE, mapOf("status" to "removed"),
            second = Op.APPLY, secondResponse = newReferral + ("status" to "already_applied"),
        )
        assertNewReferral(h.client())
    }

    @Test fun delayedApplyCannotOverwriteClaimedPerk() = runTest {
        val h = Harness(this)
        runInOrder(
            h, Op.APPLY, mapOf("status" to "applied", "perkLabel" to "pending"),
            second = Op.CLAIM, secondResponse = newReferral + ("status" to "granted"),
        )
        assertNewReferral(h.client())
    }

    @Test fun currentRequestsCannotSaveInReverseOrder() = runTest {
        val h = Harness(this)
        seed(h)
        runInOrder(
            h, Op.CURRENT, mapOf("status" to "applied", "perk" to perkJson),
            second = Op.CURRENT, secondResponse = mapOf("status" to "removed"),
        )
        assertCleared(h.client())
    }

    @Test fun failedOperationReleasesNextOperation() = runTest {
        val h = Harness(this)
        runInOrder(h, Op.CURRENT, emptyMap(), firstStatus = 503, second = Op.APPLY, secondResponse = newReferral)
        assertNewReferral(h.client())
    }

    @Test fun concurrentAppliesAreSentOneAtATime() = runTest {
        val h = Harness(this)
        val client = h.client()
        var inFlight = 0
        var maxInFlight = 0
        val gates = List(5) { CompletableDeferred<Unit>() }
        var index = 0
        h.transport.handler = {
            val gate = gates[index++]
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            gate.await()
            inFlight--
            jsonResponse("status" to "applied")
        }
        val tasks = (1..5).map { async { client.applyCode("CODE$it") } }
        repeat(5) { i ->
            runCurrent()
            assertEquals(i + 1, h.transport.calls("codes/apply").size)
            gates[i].complete(Unit)
        }
        tasks.forEach { it.await() }
        assertEquals(1, maxInFlight)
        assertEquals(
            listOf("CODE1", "CODE2", "CODE3", "CODE4", "CODE5"),
            h.transport.calls("codes/apply").map { org.json.JSONObject(it.body!!).getString("code") },
        )
    }

    @Test fun cancelledQueuedOperationSkipsRequestAndReleasesQueue() = runTest {
        val h = Harness(this)
        val client = h.client()
        val pending = CompletableDeferred<HttpResponse>()
        h.transport.handler = { request ->
            if (request.path.endsWith("codes/current")) pending.await() else jsonResponse(newReferral)
        }
        val current = async { client.currentReferral() }
        runCurrent()
        val cancelled = async { client.applyCode("MIKA") }
        runCurrent()
        cancelled.cancel()
        pending.complete(jsonResponse("status" to "none"))
        current.await()
        val result = runCatching { cancelled.await() }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(1, h.transport.requests.size)
        client.applyCode("MIKA")
        assertEquals(2, h.transport.requests.size)
        assertNewReferral(client)
    }
}
