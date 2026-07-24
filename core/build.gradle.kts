plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// Platform-neutral domain logic (M3U parsing, content classification, Xtream
// API, XMLTV, catch-up, metadata) shared by the Android app and the web
// server. commonMain has no JVM/Android dependency, so an iOS target can be
// added later without touching this code.
kotlin {
    androidLibrary {
        namespace = "com.buco7854.opentv.core"
        compileSdk = 37
        minSdk = 26
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    jvm {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            // JSON is an implementation detail of the provider/metadata adapters.
            // Domain models deliberately do not expose serialization types.
            implementation(libs.kotlinx.serialization.json)
            // api: TimeZone appears in Catchup's public signatures.
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
