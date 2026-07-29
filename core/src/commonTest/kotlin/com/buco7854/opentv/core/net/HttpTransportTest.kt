package com.buco7854.opentv.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpTransportTest {

    @Test
    fun headerLookupIsCaseInsensitive() {
        val response = HttpResponseSpec(
            status = 429,
            headers = mapOf("Retry-After" to listOf("2", "ignored")),
            bodyText = "",
        )
        assertEquals("2", response.header("retry-after"))
        assertEquals("2", response.header("RETRY-AFTER"))
        assertNull(response.header("X-Missing"))
    }

    @Test
    fun successCoversOnlyTwoHundreds() {
        assertTrue(HttpResponseSpec(200, emptyMap(), "").isSuccess)
        assertTrue(HttpResponseSpec(204, emptyMap(), "").isSuccess)
        assertFalse(HttpResponseSpec(304, emptyMap(), "").isSuccess)
        assertFalse(HttpResponseSpec(401, emptyMap(), "").isSuccess)
    }
}
