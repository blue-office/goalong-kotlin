package me.goalong.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class HttpRequest(
    val method: String,
    val url: String,
    /** JSON 本文(GET は null) */
    val body: String? = null,
)

internal data class HttpResponse(
    val statusCode: Int,
    val body: String,
)

/** 通信層。テストでは偽物に差し替える。通信失敗は IOException で知らせる */
internal fun interface HttpTransport {
    @Throws(IOException::class)
    suspend fun execute(request: HttpRequest): HttpResponse
}

/**
 * HttpURLConnection による実装。接続・読み取りにタイムアウトを付ける。
 *
 * 読み取りのタイムアウトは 1 回の read ごとなので、少しずつ届く応答では止まらない。そこで:
 * - 通信は IO スレッドで行い、呼び出し側のコルーチンはキャンセル(GoAlongClient の withTimeout による
 *   リクエスト全体の期限を含む)されたら**通信の終了を待たずに**すぐ戻る(Mutex を持ったまま止まらない)
 * - キャンセル時は connection.disconnect() でブロッキング中の読み書きを中断し、本文は小分けに読んで
 *   読むたびにキャンセルを確認する
 */
internal class UrlConnectionTransport(
    private val timeoutMs: Int = TIMEOUT_MS,
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse = suspendCancellableCoroutine { cont ->
        val connection = URL(request.url).openConnection() as HttpURLConnection
        val job = ioScope.launch {
            val result = runCatching { perform(connection, request) { isActive } }
            // キャンセル済みなら resume は無視される
            cont.resumeWith(result)
        }
        cont.invokeOnCancellation {
            job.cancel()
            runCatching { connection.disconnect() }
        }
    }

    private fun perform(connection: HttpURLConnection, request: HttpRequest, active: () -> Boolean): HttpResponse {
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (request.body != null) {
                val bytes = request.body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    if (!active()) throw IOException("cancelled")
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_RESPONSE_BYTES) throw IOException("response too large")
                }
                out.toString(Charsets.UTF_8.name())
            } ?: ""
            return HttpResponse(status, text)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val TIMEOUT_MS = 15_000
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
