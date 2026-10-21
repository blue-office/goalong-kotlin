plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 動作確認用のアプリ。公開しない(maven-publish を適用しない)
android {
    namespace = "me.goalong.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.goalong.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "GOALONG_BASE_URL", "\"https://goalong.me\"")
    }

    buildTypes {
        debug {
            // 偽の appId(サーバーは unknown_app を返す。UI が通信失敗を扱えることの確認用)
            buildConfigField("String", "GOALONG_APP_ID", "\"sample-debug-app-id\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "GOALONG_APP_ID", "\"REPLACE_WITH_YOUR_APP_ID\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":goalong"))
    implementation(project(":goalong-compose"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
}
