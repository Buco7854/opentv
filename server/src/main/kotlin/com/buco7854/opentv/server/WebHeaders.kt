package com.buco7854.opentv.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.header
import io.ktor.server.routing.Route

internal const val CONTENT_SECURITY_POLICY_HEADER = "Content-Security-Policy"
internal const val FRAME_OPTIONS_HEADER = "X-Frame-Options"
internal const val CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options"
internal const val REFERRER_POLICY_HEADER = "Referrer-Policy"
internal const val OPENER_POLICY_HEADER = "Cross-Origin-Opener-Policy"

internal val CONTENT_SECURITY_POLICY = listOf(
    "default-src 'self'",
    "base-uri 'self'",
    "object-src 'none'",
    "frame-ancestors 'none'",
    "form-action 'self'",
    "script-src 'self'",
    "style-src 'self'",
    "img-src 'self' data: blob:",
    "media-src 'self' blob: data:",
    "font-src 'self' data:",
    "connect-src 'self'",
    "worker-src 'self' blob:",
    "child-src 'self' blob:",
    "manifest-src 'self'",
).joinToString("; ")

internal const val HSTS_MAX_AGE_SECONDS = 63_072_000L

internal fun browserSecurityHeaders(secure: Boolean): List<Pair<String, String>> = buildList {
    add(CONTENT_SECURITY_POLICY_HEADER to CONTENT_SECURITY_POLICY)
    add(FRAME_OPTIONS_HEADER to "DENY")
    add(CONTENT_TYPE_OPTIONS_HEADER to "nosniff")
    add(REFERRER_POLICY_HEADER to "no-referrer")
    add(OPENER_POLICY_HEADER to "same-origin")
    // Only promise transport security to a browser that arrived over it. Sending HSTS from a
    // plain-HTTP deployment is ignored at best and locks the operator out at worst.
    if (secure) {
        add(HttpHeaders.StrictTransportSecurity to "max-age=$HSTS_MAX_AGE_SECONDS; includeSubDomains")
    }
}

internal fun Application.installOpenTvSecurityHeaders(secure: (ApplicationCall) -> Boolean) {
    val plain = browserSecurityHeaders(secure = false)
    val overHttps = browserSecurityHeaders(secure = true)
    install(
        createApplicationPlugin("OpenTvSecurityHeaders") {
            onCall { call ->
                val headers = if (secure(call)) overHttps else plain
                headers.forEach { (name, value) -> call.response.header(name, value) }
            }
        },
    )
}

internal const val WEB_RESOURCE_PACKAGE = "web"
internal const val WEB_INDEX_PAGE = "index.html"
internal const val IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable"
internal const val REVALIDATED_CACHE_CONTROL = "no-cache"

private val HASHED_ASSET = Regex("""/assets/[^/]*-[A-Za-z0-9_-]{8,}\.[A-Za-z0-9]+$""")

internal fun webCacheControl(resourcePath: String): String =
    if (HASHED_ASSET.containsMatchIn(resourcePath)) IMMUTABLE_CACHE_CONTROL
    else REVALIDATED_CACHE_CONTROL

internal fun Route.webClient(resourcePackage: String = WEB_RESOURCE_PACKAGE) {
    staticResources("/", resourcePackage, index = WEB_INDEX_PAGE) {
        default(WEB_INDEX_PAGE)
        modify { resource, call ->
            call.response.header(HttpHeaders.CacheControl, webCacheControl(resource.path))
        }
    }
}
