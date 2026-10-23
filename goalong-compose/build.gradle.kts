plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "me.goalong.sdk.compose"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":goalong"))
    // 公開 API が Modifier・Color・TextStyle を使うので runtime と ui は api にする
    api(platform(libs.compose.bom))
    api(libs.compose.runtime)
    api(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = "goalong-compose"
                version = project.version.toString()
                pom {
                    name.set("GoAlong Android SDK — Compose UI")
                    description.set("Jetpack Compose referral code entry and input for GoAlong.")
                    url.set("https://goalong.me")
                }
            }
        }
    }
}
