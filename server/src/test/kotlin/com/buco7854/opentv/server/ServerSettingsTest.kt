package com.buco7854.opentv.server

import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerSettingsTest {
    @Test
    fun persistsVersionedSettings() {
        val dir = Files.createTempDirectory("settings-test")
        val first = ServerSettings(dir, pageSize = 75)
        first.userAgent = " Test Agent "
        first.downloadLimit = 3
        val key = first.streamKey

        val second = ServerSettings(dir, pageSize = 75)
        assertEquals("Test Agent", second.userAgent)
        assertEquals(3, second.downloadLimit)
        assertEquals(key, second.streamKey)
        assertEquals(75, second.pageSize)
    }

    @Test
    fun migratesLegacyPropertiesWithoutRotatingTokens() {
        val dir = Files.createTempDirectory("settings-legacy")
        val legacy = dir.resolve("settings.properties")
        Properties().apply {
            setProperty("userAgent", "Legacy")
            setProperty("downloadLimit", "2")
            setProperty("streamKey", "stable-key")
            legacy.toFile().outputStream().use { store(it, null) }
        }

        val settings = ServerSettings(dir, pageSize = 50)

        assertEquals("Legacy", settings.userAgent)
        assertEquals(2, settings.downloadLimit)
        assertEquals("stable-key", settings.streamKey)
        assertTrue(dir.resolve("server-settings.json").exists())
        assertTrue(dir.resolve("settings.properties.bak").exists())
    }
}
