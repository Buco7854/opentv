plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
}

kotlin {
    jvm {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.room.runtime)
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies {
            implementation(libs.sqlite.bundled)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

dependencies {
    add("kspJvm", libs.room.compiler)
}

val schemaDirectory = project.layout.projectDirectory.dir("schemas")

ksp {
    arg("room.schemaLocation", schemaDirectory.asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    systemProperty("opentv.schemaDirectory", schemaDirectory.asFile.absolutePath)
}
