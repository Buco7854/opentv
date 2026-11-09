package com.buco7854.opentv.ui.hub

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HubBrowserHandoffTest {
    @Test
    fun `a trusted playlist management page becomes a phone QR on television`() {
        val context = televisionContext()
        val page = "https://tv.example/opentv/?manage=playlist"

        assertEquals(
            HandoffResult.ShowQrInstead(page),
            HubBrowserHandoff(context).open("https://tv.example/opentv", page),
        )
    }

    @Test
    fun `a foreign playlist management page is refused before offering a phone QR`() {
        assertEquals(
            HandoffResult.Rejected,
            HubBrowserHandoff(televisionContext()).open(
                "https://tv.example/opentv", "https://elsewhere.example/?manage=playlist",
            ),
        )
    }

    private fun televisionContext(): Context = ApplicationProvider.getApplicationContext<Application>().also {
        Shadows.shadowOf(it.packageManager).setSystemFeature("android.software.leanback", true)
    }
}
