plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "me.goalong.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"${project.version}\"")
    }

    buildFeatures {
        buildConfig = true
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
    // 公開 API が SharedFlow を返すので api にする
    api(libs.kotlinx.coroutines.android)
    implementation(libs.installreferrer)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // android.jar の org.json はスタブなので、JVM テストでは本物を使う
    testImplementation(libs.org.json)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = "goalong"
                version = project.version.toString()
                pom {
                    name.set("GoAlong Android SDK")
                    description.set("Creator referral codes and attribution for Android apps (GoAlong).")
                    url.set("https://goalong.me")
                }
            }
        }
    }
}
