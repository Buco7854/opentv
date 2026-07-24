package com.buco7854.opentv.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerConfigTest {
    @Test
    fun defaultsAreSafeAndNormalized() {
        val config = ServerConfig.fromEnv(emptyMap())

        assertEquals(8080, config.port)
        assertEquals(50, config.pageSize)
        assertEquals(1, config.fallbackProviderConnections)
        assertTrue(config.dataDir.isAbsolute)
    }

    @Test
    fun invalidNumbersFailFast() {
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnv(mapOf("PORT" to "70000"))
        }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnv(mapOf("OPENTV_PAGE_SIZE" to "many"))
        }
    }

    @Test
    fun legacyConnectionVariableHasActionableFailure() {
        val error = assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnv(mapOf("OPENTV_REMUX_CONNECTIONS" to "2"))
        }
        assertTrue("OPENTV_PROVIDER_CONNECTIONS" in error.message.orEmpty())
    }
}
