package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistUpdateSecurityTest {
    @Test
    fun `omitted provider fields stay absent from the wire request`() {
        val body = Json {
            encodeDefaults = true
            explicitNulls = false
        }.encodeToString(PlaylistUpdateRequest(name = "Renamed"))

        assertEquals("""{"name":"Renamed"}""", body)
    }
}
