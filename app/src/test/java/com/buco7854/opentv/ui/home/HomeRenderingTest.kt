package com.buco7854.opentv.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.hub.HubSignInState
import com.buco7854.opentv.ui.hub.SignInIntroduction
import com.buco7854.opentv.ui.hub.UrlEntryStep
import com.buco7854.opentv.ui.hub.PasswordStep
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.ui.shell.DockSection
import com.buco7854.opentv.ui.shell.OpenTvDock
import com.buco7854.opentv.ui.theme.OpenTvDesign
import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Compose layout catches Android saveable-key errors which pure state tests cannot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeRenderingTest {
    @Test
    fun `rendered Home stays put until the user explicitly opens a source`() {
        var opened: SourceId? = null
        render("home-dark", dark = true) { wide ->
            Library(wide, onOpenSource = { opened = it })
        }.use { screen ->
            assertNull(opened)
            val source = screen.nodes().single {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Family library" } == true &&
                    it.config.contains(SemanticsActions.OnClick)
            }
            assertTrue(source.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals(SourceId.LocalPlaylist(1), opened)
            listOf("Home", "Browse", "Favorites", "Search", "More").forEach { label ->
                assertTrue("Missing navigation: $label", screen.hasText(label))
            }
        }
    }

    @Test
    fun `light Home renders the same source choices`() {
        render("home-light", dark = false) { Library(it) {} }.use { screen ->
            assertTrue(screen.hasText("Library"))
            assertTrue(screen.hasText("Weekend cinema"))
            assertTrue(screen.hasText("Your playlists"))
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h720dp-mdpi")
    fun `wide navigation keeps the same five destinations`() {
        render("home-wide", dark = true, width = 1280, height = 720) { Library(it) {} }.use { screen ->
            listOf("Home", "Browse", "Favorites", "Search", "More").forEach { label ->
                assertTrue("Missing wide navigation: $label", screen.hasText(label))
            }
            assertTrue(screen.hasText("Family library"))
        }
    }

    @Test
    fun `pending discovery never presents an existing library as first run`() {
        render("home-loading", dark = true) {
            HomeLibrary(emptyList(), loading = true, unreachableHubs = 0, busy = false, {}, {}, {}, {})
        }.use { screen ->
            assertTrue(screen.hasText("Library"))
            assertFalse(screen.hasText("Connect a server"))
            assertFalse(screen.hasText("Add your first playlist"))
        }
    }

    @Test
    fun `server entry waits for explicit continue and submits the entered address`() {
        var submitted: String? = null
        render("signin-address-dark", dark = true) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    SignInIntroduction(serverStep = true, reauthenticating = false)
                    UrlEntryStep(HubSignInState.UrlEntry("https://tv.example.com")) { submitted = it }
                }
            }
        }.use { screen ->
            assertNull(submitted)
            val connect = screen.nodes().single {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Connect" } == true &&
                    it.config.contains(SemanticsActions.OnClick)
            }
            assertTrue(connect.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals("https://tv.example.com", submitted)
        }
    }

    @Test
    fun `password form renders a protected field and an accessible reveal action`() {
        render("signin-password-light", dark = false) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    SignInIntroduction(serverStep = false, reauthenticating = false)
                    PasswordStep(HubSignInState.Password(username = "alex")) { _, _ -> }
                }
            }
        }.use { screen ->
            assertTrue(screen.nodes().any {
                it.config.contains(SemanticsProperties.Password) && it.config.contains(SemanticsActions.SetText)
            })
            assertTrue(screen.nodes().any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Show password") == true &&
                    it.config.contains(SemanticsActions.OnClick)
            })
            assertTrue(screen.nodes().any {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "alex"
            })
        }
    }

    @Composable
    private fun Library(wide: Boolean, onOpenSource: (SourceId) -> Unit) {
        Scaffold(
            topBar = { if (wide) OpenTvDock(true, DockSection.HOME, {}, {}, wide = true) },
            bottomBar = { if (!wide) OpenTvDock(true, DockSection.HOME, {}, {}) },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                HomeLibrary(
                    sources = listOf(
                        CatalogSourceEntry(SourceId.LocalPlaylist(1), "Family library", itemCount = 240),
                        CatalogSourceEntry(SourceId.Hub(2, 8), "Weekend cinema", "Home server", 1280),
                        CatalogSourceEntry(SourceId.Hub(2, 9), "Live television", "Home server", 360),
                    ),
                    loading = false,
                    unreachableHubs = 0,
                    busy = false,
                    onOpenSource = onOpenSource,
                    onConnectHub = {}, onAddPlaylist = {}, onRetry = {},
                )
            }
        }
    }

    private fun render(
        name: String,
        dark: Boolean,
        width: Int = 390,
        height: Int = 844,
        content: @Composable (Boolean) -> Unit,
    ): RenderedScreen {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent { OpenTvDesign(dark) { content(width >= 840) } }
        val decor = activity.window.decorView
        val settle = {
            Snapshot.sendApplyNotifications()
            repeat(6) {
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
                decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, width, height)
            }
        }
        settle()
        // Screenshots are opt-in artifacts for manual review, never a pixel-perfect assertion.
        System.getenv("OPENTV_SCREENSHOT_DIR")?.let { directory ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            val output = File(directory).apply { mkdirs() }
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val root = views(decor).filterIsInstance<RootForTest>().single()
        return RenderedScreen({ root.semanticsOwner.rootSemanticsNode }, settle) { controller.pause().stop().destroy() }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(views(view.getChildAt(it))) }
    }

    private class RenderedScreen(
        private val root: () -> SemanticsNode,
        private val refresh: () -> Unit,
        private val dispose: () -> Unit,
    ) : AutoCloseable {
        fun nodes(): Sequence<SemanticsNode> = walk(root())
        fun settle() = refresh()
        fun hasText(text: String): Boolean = nodes().any {
            it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
        }
        private fun walk(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            node.children.forEach { yieldAll(walk(it)) }
        }
        override fun close() = dispose()
    }
}
