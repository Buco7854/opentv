package com.buco7854.opentv.hub

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowserSignInReturnTest {

    @Test
    fun `only a view intent on the sign-in scheme counts as a return`() {
        assertTrue(BrowserSignInReturn.isSignInReturn(viewIntent("opentv://sign-in")))
        assertTrue(BrowserSignInReturn.isSignInReturn(viewIntent("opentv://sign-in/done")))

        assertFalse("a launcher intent is not a return", BrowserSignInReturn.isSignInReturn(Intent(Intent.ACTION_MAIN)))
        assertFalse("no data at all", BrowserSignInReturn.isSignInReturn(Intent(Intent.ACTION_VIEW)))
        assertFalse(BrowserSignInReturn.isSignInReturn(null))
        // A different host on our scheme is some other deep link, not this one.
        assertFalse(BrowserSignInReturn.isSignInReturn(viewIntent("opentv://playback/7")))
        // And a look-alike from the open web must never be mistaken for it.
        assertFalse(BrowserSignInReturn.isSignInReturn(viewIntent("https://sign-in/")))
        assertFalse(BrowserSignInReturn.isSignInReturn(viewIntent("opentvx://sign-in")))
    }

    @Test
    fun `signalling without a waiting poll is harmless`() {
        // The redirect can arrive when nothing is listening — the user cancelled, or the
        // process was rebuilt. It must not throw or queue up work for a later sign-in.
        repeat(3) { BrowserSignInReturn.signal() }
    }

    private fun viewIntent(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
}
