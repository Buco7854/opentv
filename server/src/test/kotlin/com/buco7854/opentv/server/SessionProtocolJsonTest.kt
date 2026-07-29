package com.buco7854.opentv.server

import com.buco7854.opentv.contract.ClientFrameDto
import com.buco7854.opentv.contract.SessionCommandDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionProtocolJsonTest {
    @Test
    fun `websocket commands encode defaults like HTTP commands`() {
        val encoded = sessionProtocolJson.encodeToString(
            SessionCommandDto.serializer(),
            SessionCommandDto(type = "pause", sequence = 1),
        )

        assertTrue("\"text\":null" in encoded)
        assertTrue("\"quiet\":false" in encoded)
        assertTrue("\"generation\":null" in encoded)
    }

    @Test
    fun `websocket client frames tolerate future fields`() {
        val decoded = sessionProtocolJson.decodeFromString(
            ClientFrameDto.serializer(),
            """{"type":"heartbeat","futureField":true}""",
        )

        assertEquals("heartbeat", decoded.type)
        assertEquals(null, decoded.heartbeat)
    }
}
