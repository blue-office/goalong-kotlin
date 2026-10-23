plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// JitPack の座標: com.github.blue-office.goalong-kotlin:<module>:<version>
val goalongGroup = "com.github.blue-office.goalong-kotlin"
val goalongVersion = providers.gradleProperty("goalongVersion").getOrElse("0.1.0")

subprojects {
    group = goalongGroup
    version = goalongVersion
}
