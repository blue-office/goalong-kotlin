package me.goalong.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.util.Locale

/** サーバーに送る端末情報 */
internal data class DeviceInfo(
    val osVersion: String,
    val appVersion: String?,
    val locale: String,
    val platform: String = "android",
) {
    companion object {
        fun from(context: Context): DeviceInfo = DeviceInfo(
            osVersion = "Android ${Build.VERSION.RELEASE}",
            appVersion = appVersion(context),
            locale = Locale.getDefault().toLanguageTag(),
        )

        @Suppress("DEPRECATION")
        private fun appVersion(context: Context): String? = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}

/** 詳細ログ。導入時の切り分け用(本番では false のままにする) */
internal object GoAlongLog {
    @Volatile var enabled: Boolean = false

    /** テストでは android.util.Log が使えないので差し替える */
    @Volatile var sink: (String) -> Unit = { message ->
        try {
            Log.d("GoAlong", message)
        } catch (_: RuntimeException) {
            println("[GoAlong] $message")
        }
    }

    fun log(message: String) {
        if (enabled) sink(message)
    }
}
