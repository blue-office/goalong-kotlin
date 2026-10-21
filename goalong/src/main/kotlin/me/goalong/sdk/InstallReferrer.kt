package me.goalong.sdk

import android.content.Context
import android.os.RemoteException
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Install Referrer の取得結果 */
internal sealed interface ReferrerFetchResult {
    /** 取得できた(referrer が空のこともある) */
    data class Success(val referrer: String?) : ReferrerFetchResult

    /** この端末では恒久的に取れない(Play ストアが無い等) → none */
    data object Unavailable : ReferrerFetchResult

    /** 一時的な失敗 → unread のまま次回起動で再試行 */
    data object RetryLater : ReferrerFetchResult
}

/** Install Referrer の取得元。テストでは偽物に差し替える */
internal fun interface InstallReferrerSource {
    suspend fun fetch(): ReferrerFetchResult
}

/** Google Play の Install Referrer ライブラリによる実装 */
internal class PlayInstallReferrerSource(context: Context) : InstallReferrerSource {
    private val appContext = context.applicationContext

    override suspend fun fetch(): ReferrerFetchResult =
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connectAndRead() } ?: ReferrerFetchResult.RetryLater

    private suspend fun connectAndRead(): ReferrerFetchResult = suspendCancellableCoroutine { cont ->
        val client = InstallReferrerClient.newBuilder(appContext).build()
        fun finish(result: ReferrerFetchResult) {
            if (cont.isActive) cont.resume(result)
            runCatching { client.endConnection() }
        }
        cont.invokeOnCancellation { runCatching { client.endConnection() } }
        try {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    val result = when (responseCode) {
                        InstallReferrerResponse.OK -> try {
                            ReferrerFetchResult.Success(client.installReferrer.installReferrer)
                        } catch (_: RemoteException) {
                            ReferrerFetchResult.RetryLater
                        } catch (_: RuntimeException) {
                            ReferrerFetchResult.RetryLater
                        }
                        InstallReferrerResponse.FEATURE_NOT_SUPPORTED,
                        InstallReferrerResponse.DEVELOPER_ERROR,
                        InstallReferrerResponse.PERMISSION_ERROR -> ReferrerFetchResult.Unavailable
                        else -> ReferrerFetchResult.RetryLater
                    }
                    finish(result)
                }

                override fun onInstallReferrerServiceDisconnected() {
                    finish(ReferrerFetchResult.RetryLater)
                }
            })
        } catch (_: RuntimeException) {
            finish(ReferrerFetchResult.RetryLater)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
    }
}

/** LP が付ける referrer(`goalong_code=<CODE>`)の解析 */
internal object InstallReferrerParser {
    private const val KEY = "goalong_code"
    private const val MAX_LENGTH = 200

    /**
     * `goalong_code=<CODE>` だけを受け付ける。他のキー・重複キー・不正なエンコード・
     * 長すぎる入力・形式に合わないコードは null(= 紹介なし)
     */
    fun parse(referrer: String?): String? {
        if (referrer == null) return null
        val raw = referrer.trim()
        if (raw.isEmpty() || raw.length > MAX_LENGTH) return null
        val pairs = raw.split('&')
        if (pairs.size != 1) return null
        val pair = pairs.single()
        val eq = pair.indexOf('=')
        if (eq <= 0) return null
        val key = pair.substring(0, eq)
        val value = pair.substring(eq + 1)
        if (key != KEY || value.isEmpty() || value.contains('=')) return null
        val decoded = ReferralLink.decodeSegment(value) ?: return null
        val code = ReferralLink.normalize(decoded)
        return if (ReferralLink.isValidCode(code)) code else null
    }
}
