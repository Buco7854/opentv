import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass = "com.buco7854.opentv.server.MainKt"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

// The React client (server/webapp) compiles into src/main/resources/web so the
// server jar ships the UI. Pass -PwebappPrebuilt when the output was already
// produced (e.g. by a dedicated node stage in the Dockerfile or CI).
val buildWebapp = tasks.register<Exec>("buildWebapp") {
    workingDir = file("webapp")
    commandLine("npm", "run", "ci-build")
    inputs.dir(file("webapp/src"))
    inputs.files(file("webapp/package.json"), file("webapp/package-lock.json"), file("webapp/index.html"))
    outputs.dir(file("src/main/resources/web"))
    enabled = !project.hasProperty("webappPrebuilt")
}

tasks.processResources { dependsOn(buildWebapp) }

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.logback.classic)
    testImplementation(libs.kotlin.test)
}
