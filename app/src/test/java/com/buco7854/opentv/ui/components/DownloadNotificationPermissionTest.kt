package com.buco7854.opentv.ui.components

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadNotificationPermissionTest {
    @Test
    fun `denied notification permission is requested and does not block download`() {
        val events = mutableListOf<String>()

        launchDownloadWithNotificationPermission(
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            permissionGranted = false,
            requestPermission = { events += "permission" },
            enqueue = { events += "download" },
        )

        assertEquals(listOf("permission", "download"), events)
    }

    @Test
    fun `older Android enqueues without requesting nonexistent permission`() {
        val events = mutableListOf<String>()

        launchDownloadWithNotificationPermission(
            sdkInt = Build.VERSION_CODES.TIRAMISU - 1,
            permissionGranted = false,
            requestPermission = { events += "permission" },
            enqueue = { events += "download" },
        )

        assertEquals(listOf("download"), events)
    }

    @Test
    fun `granted notification permission enqueues without another request`() {
        val events = mutableListOf<String>()

        launchDownloadWithNotificationPermission(
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            permissionGranted = true,
            requestPermission = { events += "permission" },
            enqueue = { events += "download" },
        )

        assertEquals(listOf("download"), events)
    }
}
