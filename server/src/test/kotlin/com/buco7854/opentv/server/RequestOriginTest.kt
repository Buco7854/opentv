package com.buco7854.opentv.server

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestOriginTest {
    private val configured = URI("https://tv.example.com")

    @Test
    fun `the configured public url is same-origin`() {
        assertTrue(same("https://tv.example.com", "tv.example.com"))
        assertTrue(same("https://tv.example.com", host = null))
        assertTrue(same("https://TV.example.com", host = null))
    }

    @Test
    fun `a browser reaching the server on another address is same-origin with it`() {
        // The default OPENTV_PUBLIC_URL cannot know the LAN address, container name or dev
        // port the first visitor actually uses; the requested host does.
        assertTrue(same("http://192.168.1.10:8080", "192.168.1.10:8080", URI("http://localhost:8080")))
        assertTrue(same("http://localhost:5173", "localhost:5173", URI("http://localhost:8080")))
        assertTrue(same("http://opentv:8080", "opentv:8080", URI("http://localhost:8080")))
    }

    @Test
    fun `a TLS terminating proxy may forward plain http under the browser's host`() {
        assertTrue(same("https://tv.example.com", "tv.example.com", URI("http://localhost:8080")))
    }

    @Test
    fun `another site is never same-origin`() {
        assertFalse(same("https://evil.example", "tv.example.com"))
        assertFalse(same("https://evil.example", "evil.example.tv.example.com"))
        assertFalse(same("https://tv.example.com.evil.example", "tv.example.com"))
    }

    @Test
    fun `a port that differs from the requested one is another origin`() {
        assertFalse(same("http://localhost:5173", "localhost:8080", URI("http://localhost:8080")))
        assertTrue(same("http://localhost", "localhost:80", URI("http://localhost:8080")))
        assertTrue(same("https://tv.example.com:443", "tv.example.com"))
    }

    @Test
    fun `an unusable origin header is rejected rather than trusted`() {
        assertFalse(same(null, "tv.example.com"))
        assertFalse(same("", "tv.example.com"))
        // Sandboxed and privacy-sensitive contexts send an opaque origin.
        assertFalse(same("null", "tv.example.com"))
        assertFalse(same("file://", "tv.example.com"))
        assertFalse(same("app://tv.example.com", "tv.example.com"))
        assertFalse(same("https://tv.example.com/path", "tv.example.com"))
        assertFalse(same("not a url", "tv.example.com"))
    }

    @Test
    fun `credentials in either header do not smuggle a host past the comparison`() {
        assertFalse(same("https://tv.example.com@evil.example", "tv.example.com"))
        assertFalse(same("https://evil.example", "evil.example@tv.example.com", URI("http://localhost:8080")))
        assertFalse(same("https://evil.example", "tv.example.com/../evil.example", URI("http://localhost:8080")))
    }

    private fun same(origin: String?, host: String?, publicUrl: URI = configured) =
        RequestOrigin.isSameOrigin(origin, host, publicUrl)
}
