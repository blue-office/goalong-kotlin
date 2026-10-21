package me.goalong.sample

import android.app.Application
import me.goalong.sdk.GoAlong

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GoAlong.configure(
            context = this,
            appId = BuildConfig.GOALONG_APP_ID,
            appSlug = "sample",
            baseUrl = BuildConfig.GOALONG_BASE_URL,
            verboseLogging = BuildConfig.DEBUG,
            // 実際のアプリでは RevenueCat の CustomerInfo で判定する(README 参照)
            isActiveSubscriber = { false },
        )
    }
}
