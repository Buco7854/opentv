package com.buco7854.opentv.ui.hub

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.contract.AuthCapabilitiesDto
import com.buco7854.opentv.hub.DeviceLinkMode
import com.buco7854.opentv.hub.DeviceLinkPhase
import com.buco7854.opentv.hub.HubSignInFailure
import com.buco7854.opentv.hub.HubSignInState
import com.buco7854.opentv.ui.theme.OpenTvDesign
import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HubSignInRenderingTest {
    @Test
    fun `address submission explicitly continues to phone sign in without method choices`() {
        var submitted: String? = null
        render("signin-address") {
            SignInIntroduction(serverStep = true, reauthenticating = false)
            UrlEntryStep(HubSignInState.UrlEntry("https://tv.example.com")) { submitted = it }
        }.use { screen ->
            screen.click("Continue on phone")
            assertEquals("https://tv.example.com", submitted)
            assertFalse(screen.hasText("Username and password"))
            assertFalse(screen.hasText("Link this device"))
        }
    }

    @Test
    fun `browser retry has exactly one authentication route even when all methods are enabled`() {
        var browserStarted = false
        var passwordStarted = false
        render("signin-browser-retry") {
            SignInIntroduction(serverStep = false, reauthenticating = false, browserSignIn = true)
            SignInContinuation(
                HubSignInState.MethodChooser(BASE, CAPABILITIES, error = HubSignInFailure.UNREACHABLE),
                hasBrowser = true,
                onBrowserSignIn = { browserStarted = true },
                onPasswordSignIn = { passwordStarted = true },
            )
        }.use { screen ->
            assertFalse(screen.hasText("Username and password"))
            assertFalse(screen.hasText("Link this device"))
            screen.click("Continue in browser")
            assertTrue(browserStarted)
            assertFalse(passwordStarted)
        }
    }

    @Test
    fun `browser waiting offers reopen only with no parallel phone or password choices`() {
        var reopened = false
        render("signin-browser-waiting") {
            SignInIntroduction(serverStep = false, reauthenticating = false, browserSignIn = true)
            DeviceLinkWaiting(linkState(), showQr = false, onReopen = { reopened = true })
        }.use { screen ->
            assertTrue(screen.hasText("Waiting for sign-in…"))
            assertFalse(screen.hasText("Sign in on your phone"))
            assertFalse(screen.hasText("Username and password"))
            assertFalse(screen.hasText("Link this device"))
            screen.click("Reopen browser")
            assertTrue(reopened)
        }
    }

    @Test
    fun `television or no browser uses a single phone QR flow`() {
        render("signin-phone-qr") {
            SignInIntroduction(serverStep = false, reauthenticating = false, browserSignIn = true)
            DeviceLinkWaiting(linkState(), showQr = true)
        }.use { screen ->
            assertTrue(screen.hasText("Sign in on your phone"))
            assertFalse(screen.hasText("Reopen browser"))
            assertFalse(screen.hasText("Username and password"))
            assertFalse(screen.hasText("Link this device"))
            assertEquals(0, screen.nodes().count { it.config.contains(SemanticsActions.OnClick) })
        }
    }

    @Test
    fun `a legacy server without browser support retains native password entry`() {
        var passwordStarted = false
        render("signin-native-fallback", dark = false) {
            SignInContinuation(
                HubSignInState.MethodChooser(BASE, CAPABILITIES.copy(deviceLinkEnabled = false)),
                hasBrowser = true,
                onBrowserSignIn = {},
                onPasswordSignIn = { passwordStarted = true },
            )
        }.use { screen ->
            assertFalse(screen.hasText("Continue in browser"))
            screen.click("Username and password")
            assertTrue(passwordStarted)
        }
    }

    @Test
    fun `preparing browser sign in disables duplicate continuation`() {
        render("signin-preparing") {
            SignInContinuation(HubSignInState.MethodChooser(BASE, CAPABILITIES, busy = true), true, {}, {})
        }.use { screen ->
            val button = screen.nodes().single {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Preparing sign-in…" } == true &&
                    it.config.contains(SemanticsActions.OnClick)
            }
            assertTrue(button.config.contains(SemanticsProperties.Disabled))
            assertFalse(screen.hasText("Username and password"))
        }
    }

    private fun render(
        name: String,
        dark: Boolean = true,
        content: @Composable ColumnScope.() -> Unit,
    ): Screen {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            OpenTvDesign(dark) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp).widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
                }
            }
        }
        val decor = activity.window.decorView
        Snapshot.sendApplyNotifications()
        repeat(6) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
            decor.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 390, 844)
        }
        System.getenv("OPENTV_SCREENSHOT_DIR")?.let { path ->
            val bitmap = Bitmap.createBitmap(390, 844, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            File(path).mkdirs()
            File(path, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val owner = views(decor).filterIsInstance<RootForTest>().single()
        return Screen({ owner.semanticsOwner.rootSemanticsNode }) { controller.pause().stop().destroy() }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(views(view.getChildAt(it))) }
    }

    private class Screen(private val root: () -> SemanticsNode, private val dispose: () -> Unit) : AutoCloseable {
        fun nodes(): Sequence<SemanticsNode> = walk(root())
        private fun walk(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            node.children.forEach { yieldAll(walk(it)) }
        }
        fun hasText(text: String) = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true }
        fun click(text: String) {
            val node = nodes().single {
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true &&
                    it.config.contains(SemanticsActions.OnClick)
            }
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        }
        override fun close() = dispose()
    }

    private companion object {
        const val BASE = "https://tv.example.com"
        val CAPABILITIES = AuthCapabilitiesDto(true, true, true, true, false, "tv.example.com")
        fun linkState() = HubSignInState.DeviceLink(
            BASE, "$BASE/link#t=test-fixture&mode=sign-in", 60_000, DeviceLinkPhase.PENDING, DeviceLinkMode.BROWSER_SIGN_IN,
        )
    }
}
