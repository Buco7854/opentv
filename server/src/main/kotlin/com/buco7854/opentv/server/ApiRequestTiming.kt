package com.buco7854.opentv.server

import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory

private val ApiRequestStartedAt = AttributeKey<Long>("OpenTvApiRequestStartedAt")
private val apiTimingLog = LoggerFactory.getLogger("opentv.api")

private val ApiRequestTiming = createApplicationPlugin("OpenTvApiRequestTiming") {
    onCall { call ->
        val path = call.request.path()
        if (path.startsWith("/api/v1/") && !path.endsWith("/ws")) {
            call.attributes.put(ApiRequestStartedAt, System.nanoTime())
        }
    }
    onCallRespond { call, _ ->
        val startedAt = call.attributes.getOrNull(ApiRequestStartedAt)
        if (startedAt != null) {
            call.attributes.remove(ApiRequestStartedAt)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            if (elapsedMs >= 1_000) {
                // Record handler latency, before response streaming. Paths identify the slow
                // endpoint; query strings, bearer headers and response bodies never enter logs.
                apiTimingLog.warn(
                    "api_slow_response method={} path={} status={} elapsed_ms={}",
                    call.request.httpMethod.value,
                    call.request.path(),
                    call.response.status()?.value ?: 200,
                    elapsedMs,
                )
            }
        }
    }
}

internal fun Application.installOpenTvApiRequestTiming() {
    install(ApiRequestTiming)
}
