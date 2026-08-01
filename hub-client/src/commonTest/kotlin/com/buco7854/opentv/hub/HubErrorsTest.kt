package com.buco7854.opentv.hub

import com.buco7854.opentv.core.net.HttpResponseSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class HubErrorsTest {

    private fun response(status: Int, body: String = "", headers: Map<String, List<String>> = emptyMap()) =
        HttpResponseSpec(status, headers, body)

    @Test
    fun mapsEachStatusToItsType() {
        assertIs<HubUnauthorizedException>(hubFailure(response(401, """{"code":"unauthenticated","message":"no"}""")))
        assertIs<HubForbiddenException>(hubFailure(response(403)))
        assertIs<HubNotFoundException>(hubFailure(response(404)))
        assertIs<HubGoneException>(hubFailure(response(410, """{"code":"playback_revoked","message":"gone"}""")))
        assertIs<HubDuplicatePlaybackException>(
            hubFailure(
                response(
                    409,
                    """{"code":"same_content_already_playing","message":"another device"}""",
                ),
            ),
        )
        assertIs<HubServerException>(hubFailure(response(503)))
        assertIs<HubApiException>(hubFailure(response(400)))
    }

    @Test
    fun capacityCarriesRetryAfterMillis() {
        val error = hubFailure(
            response(429, """{"code":"auth_rate_limited"}""", mapOf("Retry-After" to listOf("2"))),
        )
        assertIs<HubCapacityException>(error)
        assertEquals(2000L, error.retryAfterMs)
        assertEquals("auth_rate_limited", error.code)
    }

    @Test
    fun malformedBodiesDegradeGracefully() {
        val error = hubFailure(response(401, "<html>gateway</html>"))
        assertIs<HubUnauthorizedException>(error)
        assertNull(error.code)
        assertEquals("HTTP 401", error.message)
    }
}
