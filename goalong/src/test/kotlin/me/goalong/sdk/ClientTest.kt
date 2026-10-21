package me.goalong.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ClientTest {
    private suspend inline fun <reified T : Throwable> assertThrows(crossinline block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName} but was $e", e)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    @Test fun installationIdIsLowercaseUuidV4AndPersisted() = runTest {
        val h = Harness(this)
        val ids = (1..20).map { async { h.client().installationId } }.awaitAll().toSet()
        assertEquals(1, ids.size)
        val id = ids.single()
        assertTrue(id, Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(id))
        assertEquals(id, h.store.values()[StorageKeys.INSTALLATION_ID])
        assertEquals(id, h.client().installationId)
    }

    @Test fun installationIsReportedOnceInBackgroundWithAndroidMeta() = runTest {
        val h = Harness(this, store = InMemoryStore())
        val client = h.client()
        client.start()
        advanceUntilIdle()
        val body = h.transport.body("installations")
        assertEquals(client.installationId, body.getString("installationId"))
        assertEquals("test-app", body.getString("appId"))
        assertEquals("0.1.0", body.getString("sdkVersion"))
        assertEquals("Android 15", body.getString("osVersion"))
        assertEquals("1.2.3", body.getString("appVersion"))
        assertEquals("ja-JP", body.getString("locale"))
        assertEquals("android", body.getString("platform"))
        assertEquals(true, h.store.values()[StorageKeys.INSTALLATION_REPORTED])
        h.client().start()
        advanceUntilIdle()
        assertEquals(1, h.transport.calls("installations").size)
    }

    @Test fun installationFailureIsRetriedOnNextLaunch() = runTest {
        val h = Harness(this, store = InMemoryStore())
        h.transport.handler = { jsonResponse("error" to "offline", status = 503) }
        h.client().start()
        advanceUntilIdle()
        assertNull(h.store.values()[StorageKeys.INSTALLATION_REPORTED])
        h.transport.handler = { FakeTransport.defaultResponse(it) }
        h.client().start()
        advanceUntilIdle()
        assertEquals(2, h.transport.calls("installations").size)
        assertEquals(true, h.store.values()[StorageKeys.INSTALLATION_REPORTED])
    }

    @Test fun validateNormalizesAndParsesPerkAndErrors() = runTest {
        val h = Harness(this)
        val client = h.client()
        h.transport.handler = {
            jsonResponse(
                "valid" to true,
                "code" to "MIKA",
                "creator" to mapOf("displayName" to "みか", "avatarUrl" to "https://example.com/a.png"),
                "perk" to mapOf("label" to "1週間無料", "entitlement" to null, "duration" to "weekly"),
            )
        }
        val validation = client.validateCode("　ｍｉｋａ　")
        assertTrue(validation.valid)
        assertEquals("みか", validation.creator?.displayName)
        assertEquals("weekly", validation.perk?.duration)
        assertNull(validation.perk?.entitlement)
        assertEquals("MIKA", h.transport.body("codes/validate").getString("code"))
        for (kind in CodeErrorKind.entries) {
            h.transport.handler = { jsonResponse("valid" to false, "error" to kind.value) }
            assertEquals(kind, client.validateCode("ABC").error)
        }
    }

    @Test fun unrelatedUrlsAndInvalidFormatsAreNeverSent() = runTest {
        val h = Harness(this)
        val client = h.client()
        for (value in listOf("https://private.example/account/SECRET", "AB", "A-B-C")) {
            assertEquals(CodeErrorKind.INVALID_FORMAT, client.validateCode(value).error)
            assertEquals(ApplyResult.NotFound(CodeErrorKind.INVALID_FORMAT), client.applyCode(value))
        }
        assertTrue(h.transport.calls("codes/validate").isEmpty())
        assertTrue(h.transport.calls("codes/apply").isEmpty())
        client.applyCode("https://goalong.me/booknotion/mika")
        assertEquals("MIKA", h.transport.body("codes/apply").getString("code"))
    }

    @Test fun applyMapsEveryStatus() = runTest {
        val h = Harness(this)
        val client = h.client()
        val cases = mapOf<String, (ApplyResult) -> Boolean>(
            "applied" to { it is ApplyResult.Applied },
            "already_applied" to { it is ApplyResult.AlreadyApplied },
            "conflict" to { it is ApplyResult.Conflict },
            "existing_subscriber" to { it == ApplyResult.ExistingSubscriber },
            "not_found" to { it == ApplyResult.NotFound(null) },
            "expired" to { it == ApplyResult.NotFound(CodeErrorKind.EXPIRED) },
        )
        for ((status, check) in cases) {
            h.transport.handler = { jsonResponse("status" to status, "creator" to null) }
            val result = client.applyCode("MIKA")
            assertTrue("$status -> $result", check(result))
        }
        h.transport.handler = { jsonResponse("status" to "not_found", "error" to "invalid_format") }
        assertEquals(ApplyResult.NotFound(CodeErrorKind.INVALID_FORMAT), client.applyCode("MIKA"))
        h.transport.handler = { jsonResponse("status" to "conflict", "currentCreator" to mapOf("displayName" to "SHO")) }
        assertEquals("SHO", (client.applyCode("MIKA") as ApplyResult.Conflict).currentCreator?.displayName)
        h.transport.handler = {
            jsonResponse(
                "status" to "applied",
                "creator" to mapOf("displayName" to "MIKA"),
                "perk" to perkJson,
                "offeringKey" to "intro",
                "perkLabel" to "label",
            )
        }
        assertEquals(
            ApplyResult.Applied(GoAlongCreator("MIKA", null), perk, "intro", "label"),
            client.applyCode("MIKA"),
        )
        assertEquals(perk, client.cachedAppliedPerk)
    }

    @Test fun applySendsConfirmReplaceMethodStoredAndExplicitIds() = runTest {
        val h = Harness(this)
        val client = h.client()
        client.setRevenueCatUserId("saved-user")
        assertTrue("claim は紹介が無ければ呼ばない", h.transport.calls("perks/claim").isEmpty())
        client.applyCode("ｍｉｋａ", method = ApplyMethod.DEEP_LINK, confirmReplace = true)
        val first = h.transport.body("codes/apply")
        assertEquals(true, first.getBoolean("confirmReplace"))
        assertEquals("saved-user", first.getString("rcAppUserId"))
        assertEquals("DEEP_LINK", first.getString("method"))
        assertEquals("MIKA", first.getString("code"))
        assertEquals("android", first.getString("platform"))
        assertEquals("Android 15", first.getString("osVersion"))
        assertEquals(client.installationId, first.getString("installationId"))
        client.applyCode("MIKA", rcAppUserId = "explicit", method = ApplyMethod.CLIPBOARD)
        val second = h.transport.body("codes/apply")
        assertEquals("explicit", second.getString("rcAppUserId"))
        assertEquals("CLIPBOARD", second.getString("method"))
        assertEquals(false, second.getBoolean("confirmReplace"))
        assertEquals("saved-user", h.store.values()[StorageKeys.RC_APP_USER_ID])
    }

    @Test fun applyWithoutRcIdOmitsTheField() = runTest {
        val h = Harness(this)
        h.client().applyCode("MIKA")
        assertFalse(h.transport.body("codes/apply").has("rcAppUserId"))
    }

    @Test fun activeSubscriberIsNotSent() = runTest {
        val h = Harness(this, subscriber = { true })
        val client = h.client()
        assertEquals(ApplyResult.ExistingSubscriber, client.applyCode("MIKA"))
        assertTrue(h.transport.calls("codes/apply").isEmpty())
        client.subscriberCheck = { false }
        assertTrue(client.applyCode("MIKA") is ApplyResult.Applied)
        assertEquals(1, h.transport.calls("codes/apply").size)
    }

    @Test fun subscriberCheckFailureContinues() = runTest {
        val h = Harness(this, subscriber = { throw IllegalStateException("unavailable") })
        h.client().applyCode("MIKA")
        assertEquals(1, h.transport.calls("codes/apply").size)
    }

    @Test fun attributionAttributesThreePatterns() = runTest {
        val h = Harness(this)
        val client = h.client()
        val id = client.installationId
        assertEquals(mapOf("goalong_installation_id" to id), client.attributionAttributes())
        client.applyCode("MIKA")
        assertEquals(
            mapOf("goalong_installation_id" to id, "goalong_referred" to "true"),
            client.attributionAttributes(),
        )
        h.transport.handler = { jsonResponse("status" to "applied", "offeringKey" to "intro") }
        client.applyCode("SHO", confirmReplace = true)
        assertEquals(
            mapOf("goalong_installation_id" to id, "goalong_referred" to "true", "goalong_offering" to "intro"),
            client.attributionAttributes(),
        )
        h.transport.handler = { jsonResponse("status" to "applied") }
        client.applyCode("ABC", confirmReplace = true)
        assertFalse(client.attributionAttributes().containsKey("goalong_offering"))
    }

    @Test fun alreadyAppliedSavesAppliedAndReplacesOffering() = runTest {
        val h = Harness(
            this,
            store = InMemoryStore(
                mapOf(StorageKeys.INSTALLATION_REPORTED to true, StorageKeys.REFERRAL_OFFERING_KEY to "intro"),
            ),
        )
        h.transport.handler = { jsonResponse("status" to "already_applied") }
        val client = h.client()
        client.applyCode("MIKA")
        assertTrue(client.hasAppliedReferral)
        assertFalse(client.attributionAttributes().containsKey("goalong_offering"))
    }

    @Test fun claimReturnsPerkSavesOfferingAndEmitsEvent() = runTest {
        val h = Harness(this)
        val client = h.client()
        client.applyCode("MIKA")
        h.transport.handler = {
            jsonResponse("status" to "granted", "offeringKey" to "intro", "perk" to perkJson)
        }
        val granted = client.setRevenueCatUserId("login-user")
        assertEquals(perk, granted)
        assertEquals("login-user", h.transport.body("perks/claim").getString("rcAppUserId"))
        assertEquals("intro", client.attributionAttributes()["goalong_offering"])
        assertEquals(listOf<GoAlongEvent>(GoAlongEvent.PerkGranted(perk)), h.events.toList())
        for (status in listOf("no_perk", "existing_subscriber")) {
            h.transport.handler = { jsonResponse("status" to status) }
            assertNull(client.setRevenueCatUserId("login-user"))
        }
        assertFalse("no_perk は offering を消す", client.attributionAttributes().containsKey("goalong_offering"))
        h.transport.handler = { jsonResponse("status" to "no_attribution") }
        assertNull(client.setRevenueCatUserId("login-user"))
        assertFalse(client.hasAppliedReferral)
    }

    @Test fun claimNetworkFailureReturnsNull() = runTest {
        val h = Harness(this)
        val client = h.client()
        client.applyCode("MIKA")
        h.transport.handler = { throw IOException("offline") }
        assertNull(client.setRevenueCatUserId("user"))
        assertTrue(client.hasAppliedReferral)
    }

    @Test fun removeClearsOnSuccessAndKeepsOnFailure() = runTest {
        val h = Harness(this)
        val client = h.client()
        h.transport.handler = { jsonResponse("status" to "applied", "offeringKey" to "intro", "perk" to perkJson) }
        client.applyCode("MIKA")
        h.transport.handler = { HttpResponse(500, "{}") }
        assertEquals(500, assertThrows<GoAlongException.Server> { client.removeReferral() }.statusCode)
        assertTrue(client.hasAppliedReferral)
        h.transport.handler = { jsonResponse("status" to "removed") }
        assertTrue(client.removeReferral())
        assertFalse(client.hasAppliedReferral)
        assertNull(client.cachedAppliedPerk)
        assertEquals(listOf("goalong_installation_id"), client.attributionAttributes().keys.toList())
        h.store.putBoolean(StorageKeys.REFERRAL_APPLIED, true)
        h.transport.handler = { jsonResponse("status" to "no_attribution") }
        assertFalse(client.removeReferral())
        assertFalse(client.hasAppliedReferral)
    }

    @Test fun currentReferralParsesDatesPerkNoneAndRemoved() = runTest {
        val h = Harness(this)
        val client = h.client()
        h.transport.handler = {
            jsonResponse(
                "status" to "applied",
                "creator" to mapOf("displayName" to "MIKA"),
                "appliedAt" to "2026-01-01T00:00:00.000Z",
                "perk" to mapOf("label" to "無料", "expiresAtMs" to 4_000_000_000_000L),
            )
        }
        val applied = client.currentReferral() as GoAlongReferralSnapshot.Applied
        assertEquals(1_767_225_600_000L, applied.appliedAtMs)
        assertEquals("無料", applied.perk?.label)
        assertNull(applied.perk?.entitlement)
        assertEquals(GoAlongPerk("無料", null, 4_000_000_000_000L), client.cachedAppliedPerk)
        assertTrue(client.hasAppliedReferral)
        assertEquals(
            mapOf("appId" to "test-app", "installationId" to client.installationId),
            h.transport.query("codes/current"),
        )
        h.transport.handler = { jsonResponse("status" to "removed", "removedAt" to "2026-01-02T00:00:00Z") }
        assertEquals(GoAlongReferralSnapshot.Removed(1_767_312_000_000L), client.currentReferral())
        assertFalse(client.hasAppliedReferral)
        h.transport.handler = { jsonResponse("status" to "none") }
        assertEquals(GoAlongReferralSnapshot.None, client.currentReferral())
        assertNull(client.cachedAppliedPerk)
    }

    @Test fun expiredCachedPerkIsNotReturned() = runTest {
        val h = Harness(this)
        val client = h.client()
        h.transport.handler = {
            jsonResponse("status" to "applied", "perk" to mapOf("label" to "x", "expiresAtMs" to h.nowMs + 1000))
        }
        client.applyCode("MIKA")
        assertEquals("x", client.cachedAppliedPerk?.label)
        h.nowMs += 1000
        assertNull(client.cachedAppliedPerk)
    }

    @Test fun configIsCachedForServerTtlAndConcurrentRequestsAreMerged() = runTest {
        val h = Harness(this)
        val client = h.client()
        val results = (1..8).map { async { client.referralEntryEnabled() } }.awaitAll()
        assertTrue(results.all { it })
        assertEquals(1, h.transport.calls("config").size)
        h.nowMs += 59_000
        assertTrue(client.referralEntryEnabled())
        assertEquals(1, h.transport.calls("config").size)
        h.nowMs += 1_000
        h.transport.handler = { jsonResponse("entryEnabled" to false, "cacheMaxAgeSeconds" to 0) }
        assertFalse(client.referralEntryEnabled())
        assertFalse(client.referralEntryEnabled())
        assertEquals(3, h.transport.calls("config").size)
        assertEquals(mapOf("appId" to "test-app"), h.transport.query("config"))
        assertTrue(h.store.values().keys.none { it.contains("entry") })
    }

    @Test fun configFailureReturnsLastValueOrFalseAndIsNotCached() = runTest {
        val h = Harness(this)
        val client = h.client()
        h.transport.handler = { throw IOException("offline") }
        assertFalse(client.referralEntryEnabled())
        h.transport.handler = { jsonResponse("entryEnabled" to true, "cacheMaxAgeSeconds" to 0) }
        assertTrue(client.referralEntryEnabled())
        h.transport.handler = { HttpResponse(503, "{}") }
        assertTrue(client.referralEntryEnabled())
        assertEquals(3, h.transport.calls("config").size)
    }

    @Test fun distinguishesNetworkInvalidJsonAndServerErrors() = runTest {
        val h = Harness(this)
        val client = h.client()
        h.transport.handler = { throw IOException("offline") }
        assertThrows<GoAlongException.Network> { client.applyCode("MIKA") }
        h.transport.handler = { HttpResponse(200, "not json") }
        assertThrows<GoAlongException.Network> { client.applyCode("MIKA") }
        h.transport.handler = { jsonResponse("status" to "unknown") }
        assertThrows<GoAlongException.Network> { client.applyCode("MIKA") }
        h.transport.handler = { HttpResponse(429, "not json") }
        assertEquals(429, assertThrows<GoAlongException.Server> { client.applyCode("MIKA") }.statusCode)
        h.transport.handler = { jsonResponse("status" to "applied", status = 201) }
        assertTrue(client.applyCode("MIKA") is ApplyResult.Applied)
    }

    @Test fun requestsTimeOutAfter15Seconds() = runTest {
        val h = Harness(this)
        val client = h.client()
        h.transport.handler = { CompletableDeferred<HttpResponse>().await() }
        val result = async { runCatching { client.validateCode("MIKA") } }
        runCurrent()
        advanceTimeBy(14_999)
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(result.await().exceptionOrNull() is GoAlongException.Network)
    }

    @Test fun usesConfiguredBaseUrl() = runTest {
        val h = Harness(this, baseUrl = "https://ref.example.com/")
        val client = h.client()
        client.applyCode("MIKA")
        assertTrue(h.transport.requests.last().url.startsWith("https://ref.example.com/api/sdk/v1/codes/apply"))
        assertEquals("ref.example.com", client.expectedHost)
        assertEquals("ABC", client.extractCode("https://links.ref.example.com/booknotion/abc"))
        assertNull(client.extractCode("https://goalong.me/booknotion/ABC"))
    }

    @Test fun responseParsingHandlesNullsAndNumbers() {
        assertNull(GoAlongClient.parsePerk(null))
        assertNull(GoAlongClient.parsePerk(org.json.JSONObject("""{"label":null}""")))
        assertEquals(
            GoAlongPerk("x", null, 2_000_000_000_000L, null),
            GoAlongClient.parsePerk(org.json.JSONObject("""{"label":"x","entitlement":null,"expiresAtMs":2.0E12}""")),
        )
        assertEquals(GoAlongCreator("", null), GoAlongClient.parseCreator(org.json.JSONObject("{}")))
        assertEquals(1_767_225_600_000L, GoAlongClient.parseIsoDate("2026-01-01T09:00:00+09:00"))
        assertNull(GoAlongClient.parseIsoDate("yesterday"))
    }
}
