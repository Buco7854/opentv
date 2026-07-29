plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

// The server's wire DTOs (@Serializable data classes only), shared verbatim by
// :server and the Android hub client so the contract is compiler-checked on
// both ends. Domain->DTO mapping stays in :server; the TypeScript mirror in
// server/webapp/src/api.ts is maintained by hand per AGENTS.md.
kotlin {
    androidLibrary {
        namespace = "com.buco7854.opentv.contract"
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
            api(libs.kotlinx.serialization.json)
        }
    }
}
