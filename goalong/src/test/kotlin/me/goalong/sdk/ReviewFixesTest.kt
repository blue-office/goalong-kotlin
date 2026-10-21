package me.goalong.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

/** 公開前レビュー(Codex)の指摘ごとの再現テスト */
class ReviewFixesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun referrer(value: String?) = FakeReferrerSource { ReferrerFetchResult.Success(value) }

    // 1【高】取得前(UNREAD)に取り消しても、後から届いた referrer で紹介が復活しない

    @Test fun removalBeforeReferrerIsReadSuppressesAutoApply() = runTest {
        val gate = CompletableDeferred<ReferrerFetchResult>()
        val h = Harness(this, referrer = FakeReferrerSource { gate.await() })
        val client = h.client()
        client.start()
        runCurrent()
        assertEquals(InstallReferrerState.UNREAD, client.installReferrerState)
        assertTrue(client.removeReferral())
        assertEquals(InstallReferrerState.INELIGIBLE, client.installReferrerState)
        // 取得中だった referrer が後から届いても書き込まない
        gate.complete(ReferrerFetchResult.Success("goalong_code=MIKA"))
        advanceUntilIdle()
        client.setRevenueCatUserId("rc")
        client.onReferralEntryShown()
        assertEquals(InstallReferrerState.INELIGIBLE, client.installReferrerState)
        assertTrue(h.transport.calls("codes/apply").isEmpty())
        // 次回起動でも読み直さない
        val restarted = h.client()
        restarted.start()
        advanceUntilIdle()
        assertEquals(1, h.referrer!!.fetchCount)
        assertTrue(h.transport.calls("codes/apply").isEmpty())
    }

    @Test fun serverRemovedBeforeReferrerIsReadSuppressesAutoApply() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        val client = h.client()
        h.transport.handler = { jsonResponse("status" to "removed") }
        client.currentReferral()
        assertEquals(InstallReferrerState.INELIGIBLE, client.installReferrerState)
        h.transport.handler = { FakeTransport.defaultResponse(it) }
        client.start()
        advanceUntilIdle()
        client.onReferralEntryShown()
        assertEquals(0, h.referrer!!.fetchCount)
        assertTrue(h.transport.calls("codes/apply").isEmpty())
    }

    @Test fun suppressionIsSavedTogetherWithClearingReferral() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        val client = h.client()
        h.store.putBoolean(StorageKeys.REFERRAL_APPLIED, true)
        h.store.failWrites = true
        try {
            client.removeReferral()
            fail("保存に失敗したら例外")
        } catch (_: GoAlongException.Storage) {
        }
        // どちらも反映されていない(片方だけ保存されることはない)
        assertTrue(client.hasAppliedReferral)
        assertEquals(InstallReferrerState.UNREAD, client.installReferrerState)
        h.store.failWrites = false
        client.removeReferral()
        assertFalse(client.hasAppliedReferral)
        assertEquals(InstallReferrerState.INELIGIBLE, client.installReferrerState)
    }

    @Test fun noneStatusDoesNotSuppress() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        val client = h.client()
        client.currentReferral()
        assertEquals(InstallReferrerState.UNREAD, client.installReferrerState)
    }

    // 2【高】購読判定の中で GoAlong の API を待っても止まらない

    @Test fun subscriberCheckMayCallSdkWithoutDeadlock() = runTest {
        val h = Harness(this)
        val client = h.client()
        client.subscriberCheck = { client.currentReferral() is GoAlongReferralSnapshot.Applied }
        assertTrue(client.applyCode("MIKA") is ApplyResult.Applied)
        assertEquals(1, h.transport.calls("codes/current").size)
    }

    @Test fun referrerSubscriberCheckMayCallSdkWithoutDeadlock() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        val client = h.client()
        client.start()
        advanceUntilIdle()
        var checks = 0
        client.subscriberCheck = {
            checks++
            client.currentReferral()
            false
        }
        client.onReferralEntryShown()
        assertEquals(1, checks)
        assertEquals(InstallReferrerState.APPLIED, client.installReferrerState)
    }

    @Test fun referrerStateIsRecheckedAfterTakingTheLock() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        val client = h.client()
        client.start()
        advanceUntilIdle()
        // 購読判定(ロックの外)の間に手入力の適用が完了した
        client.subscriberCheck = {
            client.subscriberCheck = null
            client.applyCode("SHO")
            false
        }
        client.onReferralEntryShown()
        assertEquals(1, h.transport.calls("codes/apply").size)
        assertEquals(InstallReferrerState.INELIGIBLE, client.installReferrerState)
    }

    // 3【高】保存に失敗したら状態を確定せず、呼び出し元に返す

    @Test fun failedWriteKeepsOldStateInMemoryAndOnDisk() {
        val file = File(folder.root, "goalong.json")
        val io = FlakyFileIo(JvmAtomicFileIo(file))
        val store = JsonFileStore(io)
        store.putString(StorageKeys.REFERRAL_OFFERING_KEY, "old")
        io.failWrites = true
        try {
            store.putString(StorageKeys.REFERRAL_OFFERING_KEY, "new")
            fail("StorageException が出るはず")
        } catch (_: StorageException) {
        }
        assertEquals("old", store.getString(StorageKeys.REFERRAL_OFFERING_KEY))
        assertEquals("old", JsonFileStore(JvmAtomicFileIo(file)).getString(StorageKeys.REFERRAL_OFFERING_KEY))
        assertTrue("旧ファイルは消さない", file.exists())
    }

    @Test fun unreadableFileIsNeverOverwritten() {
        val file = File(folder.root, "goalong.json")
        JsonFileStore(JvmAtomicFileIo(file)).putString(StorageKeys.INSTALLATION_ID, "keep")
        val io = FlakyFileIo(JvmAtomicFileIo(file))
        io.failReads = true
        val store = JsonFileStore(io)
        assertNull(store.getString(StorageKeys.INSTALLATION_ID))
        try {
            store.putString(StorageKeys.RC_APP_USER_ID, "rc")
            fail("読めないファイルは上書きしない")
        } catch (_: StorageException) {
        }
        assertEquals("keep", JsonFileStore(JvmAtomicFileIo(file)).getString(StorageKeys.INSTALLATION_ID))
        io.failReads = false
        assertEquals("keep", store.getString(StorageKeys.INSTALLATION_ID))
    }

    @Test fun installationIdThatCannotBeSavedIsNeverSent() = runTest {
        val h = Harness(this, store = InMemoryStore())
        h.store.failWrites = true
        val client = h.client()
        client.start()
        advanceUntilIdle()
        assertTrue(h.transport.calls("installations").isEmpty())
        try {
            client.applyCode("MIKA")
            fail("Storage が出るはず")
        } catch (_: GoAlongException.Storage) {
        }
        assertTrue(h.transport.calls("codes/apply").isEmpty())
        assertNull(h.store.values()[StorageKeys.INSTALLATION_ID])
        h.store.failWrites = false
        client.start()
        advanceUntilIdle()
        assertEquals(h.store.values()[StorageKeys.INSTALLATION_ID], h.transport.body("installations").getString("installationId"))
    }

    @Test fun failedSaveOfApplyResultIsReportedAndNotCached() = runTest {
        val h = Harness(this)
        val client = h.client()
        client.installationId
        h.store.failWrites = true
        try {
            client.applyCode("MIKA")
            fail("Storage が出るはず")
        } catch (_: GoAlongException.Storage) {
        }
        assertFalse(client.hasAppliedReferral)
    }

    // 4【中】referrer の自動適用で付与された特典を、追加の claim で消さない

    @Test fun setRevenueCatUserIdReturnsPerkFromAutoApplyWithoutExtraClaim() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        val client = h.client()
        client.start()
        advanceUntilIdle()
        h.transport.handler = { request ->
            when (request.path) {
                "/api/sdk/v1/codes/apply" -> jsonResponse("status" to "applied", "perk" to perkJson, "offeringKey" to "intro")
                // 二重付与しないサーバーは perk なしで返す
                "/api/sdk/v1/perks/claim" -> jsonResponse("status" to "granted")
                else -> FakeTransport.defaultResponse(request)
            }
        }
        val granted = client.setRevenueCatUserId("rc-1")
        assertEquals(perk, granted)
        assertTrue(h.transport.calls("perks/claim").isEmpty())
        assertEquals(perk, client.cachedAppliedPerk)
        assertEquals("intro", client.attributionAttributes()["goalong_offering"])
        assertEquals("rc-1", h.transport.body("codes/apply").getString("rcAppUserId"))
    }

    @Test fun autoApplyWithoutPerkStillClaims() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        val client = h.client()
        client.start()
        advanceUntilIdle()
        h.transport.handler = { request ->
            when (request.path) {
                "/api/sdk/v1/perks/claim" -> jsonResponse("status" to "granted", "perk" to perkJson)
                else -> FakeTransport.defaultResponse(request)
            }
        }
        assertEquals(perk, client.setRevenueCatUserId("rc-1"))
        assertEquals(1, h.transport.calls("perks/claim").size)
    }

    @Test fun sameRcIdAgainKeepsCachedPerk() = runTest {
        val h = Harness(this, referrer = referrer("goalong_code=MIKA"))
        val client = h.client()
        client.start()
        advanceUntilIdle()
        h.transport.handler = { request ->
            when (request.path) {
                "/api/sdk/v1/codes/apply" -> jsonResponse("status" to "applied", "perk" to perkJson)
                "/api/sdk/v1/perks/claim" -> jsonResponse("status" to "granted")
                else -> FakeTransport.defaultResponse(request)
            }
        }
        assertEquals(perk, client.setRevenueCatUserId("rc-1"))
        // 同じ ID でもう一度呼ぶと claim するが、付与済みの応答(perk なし)でもキャッシュを消さず、特典を返す
        assertEquals(perk, client.setRevenueCatUserId("rc-1"))
        assertEquals(perk, client.cachedAppliedPerk)
        assertEquals(1, h.transport.calls("perks/claim").size)
    }

    @Test fun grantedWithoutPerkDoesNotEraseCachedPerk() = runTest {
        val h = Harness(this)
        val client = h.client()
        h.transport.handler = { request ->
            when (request.path) {
                "/api/sdk/v1/codes/apply" -> jsonResponse("status" to "applied", "perk" to perkJson)
                "/api/sdk/v1/perks/claim" -> jsonResponse("status" to "granted")
                else -> FakeTransport.defaultResponse(request)
            }
        }
        client.applyCode("MIKA", ApplyMethod.CODE, rcAppUserId = "rc-1")
        // 別の ID で claim → 付与済みの応答(perk なし)でも、保存済みの特典は残る
        client.setRevenueCatUserId("rc-2")
        assertEquals(perk, client.cachedAppliedPerk)
    }

    // 3【高】本番の書き込み(一時ファイル + fsync + rename)は、置き換えに失敗したら例外にして旧ファイルを残す

    @Test fun androidAtomicWriteFailsLoudlyWhenRenameFails() {
        val target = folder.newFolder("target-is-a-dir")
        File(target, "child").writeText("x") // 空でないディレクトリには rename できない
        val io = AndroidAtomicFileIo(target)
        try {
            io.write("new".toByteArray())
            fail("rename の失敗が例外にならない")
        } catch (_: java.io.IOException) {
        }
        assertTrue(File(target, "child").exists())
        assertFalse(File(target.path + ".tmp").exists())
    }

    @Test fun androidAtomicWriteRoundTrips() {
        val file = File(folder.root, "goalong.json")
        val io = AndroidAtomicFileIo(file)
        io.write("{\"a\":1}".toByteArray())
        io.write("{\"a\":2}".toByteArray())
        assertEquals("{\"a\":2}", String(io.read()!!))
        assertNull(AndroidAtomicFileIo(File(folder.root, "missing.json")).read())
    }

    // 5【中】少しずつ届く応答でも、期限が来たら接続を切って抜ける

    @Test fun tricklingResponseIsInterruptedByCancellation() {
        val server = ServerSocket(0)
        val serverThread = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) Unit
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".toByteArray())
                    out.flush()
                    repeat(1_000) {
                        out.write('{'.code)
                        out.flush()
                        Thread.sleep(100)
                    }
                }
            }
        }
        val transport = UrlConnectionTransport(timeoutMs = 15_000)
        val started = System.nanoTime()
        try {
            runBlocking {
                withTimeout(1_000) {
                    transport.execute(HttpRequest("GET", "http://127.0.0.1:${server.localPort}/api/sdk/v1/config"))
                }
            }
            fail("期限で中断されるはず")
        } catch (_: TimeoutCancellationException) {
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("1 秒の期限から大きく遅れない: ${elapsedMs}ms", elapsedMs < 5_000)
        server.close()
        serverThread.interrupt()
    }

    @Test fun clientRequestHasOverallDeadline() = runTest {
        val h = Harness(this)
        val client = h.client()
        h.transport.handler = { CompletableDeferred<HttpResponse>().await() }
        val pending = async { runCatching { client.applyCode("MIKA") } }
        advanceUntilIdle()
        assertTrue(pending.await().exceptionOrNull() is GoAlongException.Network)
        // 期限切れの後はロックが解放され、次の操作が進む
        h.transport.handler = { FakeTransport.defaultResponse(it) }
        assertTrue(client.applyCode("MIKA") is ApplyResult.Applied)
    }
}
