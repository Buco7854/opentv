plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.ksp)
}

// Room/SQLite adapter for :core's storage ports. The only module that knows
// the persistence technology; swapping SQLite out means re-implementing
// core.storage.Storage here (or in a sibling module), nothing else.
kotlin {
    androidLibrary {
        namespace = "com.buco7854.opentv.data"
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
            api(project(":core"))
            implementation(libs.room.runtime)
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies {
            implementation(libs.sqlite.bundled)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
}

val schemaDirectory = project.layout.projectDirectory.dir("schemas")

ksp {
    arg("room.schemaLocation", schemaDirectory.asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    systemProperty("opentv.schemaDirectory", schemaDirectory.asFile.absolutePath)
}
