package me.goalong.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections

internal fun jsonResponse(body: Map<String, Any?>, status: Int = 200): HttpResponse =
    HttpResponse(status, JSONObject(body.mapValues { it.value ?: JSONObject.NULL }).toString())

internal fun jsonResponse(vararg pairs: Pair<String, Any?>, status: Int = 200): HttpResponse =
    jsonResponse(mapOf(*pairs), status)

internal val perkJson = mapOf("label" to "1週間無料", "entitlement" to "pro", "expiresAtMs" to 4_000_000_000_000L)
internal val perk = GoAlongPerk(label = "1週間無料", entitlement = "pro", expiresAtMs = 4_000_000_000_000L)

/** 呼ばれた通信を記録し、handler で応答を返す偽の通信層 */
internal class FakeTransport : HttpTransport {
    val requests: MutableList<HttpRequest> = Collections.synchronizedList(mutableListOf())

    @Volatile var handler: suspend (HttpRequest) -> HttpResponse = { defaultResponse(it) }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests.add(request)
        return handler(request)
    }

    fun calls(path: String): List<HttpRequest> =
        requests.toList().filter { it.path == "/api/sdk/v1/$path" }

    fun body(path: String): JSONObject = JSONObject(calls(path).last().body!!)

    fun query(path: String): Map<String, String> =
        calls(path).last().url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    companion object {
        fun defaultResponse(request: HttpRequest): HttpResponse = when (request.path) {
            "/api/sdk/v1/config" -> jsonResponse("entryEnabled" to true, "cacheMaxAgeSeconds" to 60)
            "/api/sdk/v1/codes/validate" -> jsonResponse("valid" to true, "code" to "MIKA")
            "/api/sdk/v1/codes/apply" -> jsonResponse("status" to "applied")
            "/api/sdk/v1/codes/current" -> jsonResponse("status" to "none")
            "/api/sdk/v1/codes/remove" -> jsonResponse("status" to "removed")
            "/api/sdk/v1/perks/claim" -> jsonResponse("status" to "no_perk")
            "/api/sdk/v1/installations" -> jsonResponse("ok" to true)
            else -> HttpResponse(404, "{}")
        }
    }
}

internal val HttpRequest.path: String
    get() = java.net.URI(url).path

/** 偽の Install Referrer。fetch の結果を後から渡せる */
internal class FakeReferrerSource(var result: suspend () -> ReferrerFetchResult) : InstallReferrerSource {
    var fetchCount = 0
    override suspend fun fetch(): ReferrerFetchResult {
        fetchCount++
        return result()
    }
}

internal val testDevice = DeviceInfo(osVersion = "Android 15", appVersion = "1.2.3", locale = "ja-JP")

/** テスト用のクライアント。通信・保存・時計・Install Referrer をすべて偽物にする */
internal class Harness(
    val scope: TestScope,
    val store: InMemoryStore = InMemoryStore(mapOf(StorageKeys.INSTALLATION_REPORTED to true)),
    val transport: FakeTransport = FakeTransport(),
    var referrer: FakeReferrerSource? = null,
    var subscriber: (suspend () -> Boolean)? = null,
    var appSlug: String? = "booknotion",
    var baseUrl: String = "https://goalong.me",
) {
    var nowMs: Long = 1_800_000_000_000L
    val events: MutableList<GoAlongEvent> = Collections.synchronizedList(mutableListOf())
    private val dispatcher = StandardTestDispatcher(scope.testScheduler)

    /** backgroundScope の処理は advanceUntilIdle で進まないので、同じスケジューラの別スコープを使う */
    val clientScope = CoroutineScope(SupervisorJob() + dispatcher)

    fun client(): GoAlongClient = GoAlongClient(
        appId = "test-app",
        appSlug = appSlug,
        baseUrl = baseUrl,
        store = store,
        transport = transport,
        device = testDevice,
        referrerSource = referrer,
        subscriberCheck = subscriber,
        scope = clientScope,
        dispatcher = dispatcher,
        now = { nowMs },
        emit = { events.add(it) },
        sdkVersion = "0.1.0",
    )
}

/** JVM 用: 一時ファイルに書いて fsync し、ATOMIC_MOVE で置き換える(旧ファイルは消さない) */
internal class JvmAtomicFileIo(private val file: File) : AtomicFileIo {
    override fun read(): ByteArray? = if (file.exists()) file.readBytes() else null

    override fun write(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".new")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }
}


/** 書き込みを失敗させられるファイル IO */
internal class FlakyFileIo(private val delegate: AtomicFileIo) : AtomicFileIo {
    @Volatile var failWrites = false
    @Volatile var failReads = false
    override fun read(): ByteArray? {
        if (failReads) throw IOException("read failed (test)")
        return delegate.read()
    }
    override fun write(bytes: ByteArray) {
        if (failWrites) throw IOException("disk full (test)")
        delegate.write(bytes)
    }
}
