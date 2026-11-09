package com.buco7854.opentv.ui.shell

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.source.CatalogGroup
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.ui.browse.BrowseCategories
import com.buco7854.opentv.ui.browse.BrowseTabs
import com.buco7854.opentv.ui.browse.BrowseHeading
import com.buco7854.opentv.ui.browse.BrowseToolbar
import com.buco7854.opentv.ui.browse.FilterField
import com.buco7854.opentv.ui.components.ScreenHeader
import com.buco7854.opentv.ui.home.CatalogSourceEntry
import com.buco7854.opentv.ui.home.HomeLibrary
import com.buco7854.opentv.ui.theme.OpenTvDesign
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
class LibraryNavigationRenderingTest {
    @Test
    fun `phone nav hover stays separated from the selected neighbor and touch opens its destination`() {
        var selected: DockSection? = null
        render("nav-phone", content = { Scene(onSection = { selected = it }) }).use { screen ->
            val home = screen.destination("Home")
            val browse = screen.destination("Browse")
            screen.assertSpacedTargets(listOf("Home", "Browse", "Favorites", "Search", "More"), 8f)
            val before = browse.boundsInRoot
            screen.hover(browse)
            screen.awaitBackground(browse, screen.backgroundAt(home))
            screen.capture("nav-phone-hover")
            assertEquals(before, screen.destination("Browse").boundsInRoot)
            assertTrue(screen.destination("Browse").boundsInRoot.left - home.boundsInRoot.right >= 8f)
            screen.tap(screen.destination("Browse"))
            assertEquals(DockSection.BROWSE, selected)
        }
    }

    @Test
    @Config(qualifiers = "fr-w320dp-h844dp-mdpi")
    fun `small French phone keeps five independent accessible touch targets`() {
        render("nav-phone-fr-320", width = 320, content = { Scene() }).use { screen ->
            screen.assertSpacedTargets(listOf("Accueil", "Parcourir", "Favoris", "Recherche", "Plus"), 8f)
            listOf("Accueil", "Parcourir", "Favoris", "Recherche", "Plus").forEach { name ->
                val target = screen.destination(name)
                assertTrue(target.boundsInRoot.right <= 320f)
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(target.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
                assertFalse("Truncated navigation label: $name", layouts.any { result ->
                    (0 until result.lineCount).any(result::isLineEllipsized)
                })
            }
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h720dp-television-mdpi")
    fun `TV nav focus does not grow into its neighbors and remote activation opens the next destination`() {
        var selected: DockSection? = null
        render("nav-tv", width = 1280, height = 720, keyboard = true, content = { Scene(wide = true, onSection = { selected = it }) }).use { screen ->
            screen.assertSpacedTargets(listOf("Home", "Browse", "Favorites", "Search", "More"), 12f)
            val homeBefore = screen.destination("Home").boundsInRoot
            screen.focus(screen.destination("Home"))
            screen.awaitFocusRing(screen.destination("Home"))
            screen.capture("nav-tv-focus-home")
            assertEquals(homeBefore, screen.destination("Home").boundsInRoot)
            screen.key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(screen.destination("Browse").config.getOrNull(SemanticsProperties.Focused) == true)
            screen.awaitFocusRing(screen.destination("Browse"))
            screen.capture("nav-tv-focus-browse")
            screen.key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(DockSection.BROWSE, selected)
        }
    }

    @Test
    fun `global masthead exposes source switching and brand home on a source-less page`() {
        var chose = false
        var home = false
        render("nav-header-light", dark = false) {
            Scene(onChoose = { chose = true }, onHome = { home = true })
        }.use { screen ->
            val source = screen.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { label -> label.contains("Family library") && label.contains("Switch playlist") } == true
            }
            assertTrue(source.config[SemanticsActions.OnClick].action!!.invoke())
            assertTrue(chose)
            val brand = screen.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("OpenTV · Home") == true
            }
            assertTrue(brand.config[SemanticsActions.OnClick].action!!.invoke())
            assertTrue(home)
        }
    }

    @Test
    fun `disabled source-dependent destinations remain unavailable while Home Favorites and More work`() {
        render("nav-empty", content = { Scene(hasPlaylist = false) }).use { screen ->
            assertTrue(screen.destination("Browse").config.contains(SemanticsProperties.Disabled))
            assertTrue(screen.destination("Search").config.contains(SemanticsProperties.Disabled))
            assertFalse(screen.destination("Home").config.contains(SemanticsProperties.Disabled))
            assertFalse(screen.destination("Favorites").config.contains(SemanticsProperties.Disabled))
            assertFalse(screen.destination("More").config.contains(SemanticsProperties.Disabled))
        }
    }

    @Test
    fun `Browse tabs retain zero and unknown counts and stay separated under focus`() {
        var chosen: Int? = null
        render("browse-tabs", keyboard = true) {
            BrowseTabs(ChannelKind.LIVE, mapOf(ChannelKind.LIVE to 20, ChannelKind.MOVIE to 0)) { chosen = it }
        }.use { screen ->
            assertTrue(screen.hasText("0"))
            assertTrue(screen.hasText("—"))
            val live = screen.destination("Live")
            val movies = screen.destination("Movies")
            assertTrue(movies.boundsInRoot.left - live.boundsInRoot.right >= 24f)
            screen.focus(movies)
            screen.awaitFocusRing(screen.destination("Movies"))
            screen.capture("browse-tabs-focus")
            assertTrue(screen.destination("Movies").boundsInRoot.left - screen.destination("Live").boundsInRoot.right >= 24f)
            assertTrue(screen.destination("Movies").config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals(ChannelKind.MOVIE, chosen)
        }
    }

    @Test
    fun `Browse categories use the same two-column phone cards and open the selected category`() {
        var selected: String? = null
        render("browse-categories") {
            BrowseCategories(listOf(CatalogGroup("News", 20), CatalogGroup("Sports", 8), CatalogGroup("Documentaries", 12)), null) { selected = it }
        }.use { screen ->
            val news = screen.clickableText("News")
            val sports = screen.clickableText("Sports")
            assertEquals(news.boundsInRoot.top, sports.boundsInRoot.top)
            assertTrue(sports.boundsInRoot.left - news.boundsInRoot.right >= 8f)
            assertTrue(sports.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals("Sports", selected)
        }
    }

    @Test
    @Config(qualifiers = "fr-w320dp-h844dp-mdpi")
    fun `long French category has a separate reachable toolbar on the narrowest phone`() {
        val title = "Films et documentaires · Histoire, sciences et civilisations"
        var backed = false
        var grid by mutableStateOf(false)
        render("category-header-fr-320", width = 320) {
            BrowseScene(title, grid = grid, onBack = { backed = true }, onToggle = { grid = !grid })
        }.use { screen ->
            screen.assertCategoryToolbar(title, "Vue grille")
            screen.tap(screen.button("Vue grille"))
            assertTrue(grid)
            assertTrue(screen.button("Vue liste").boundsInRoot.width >= 48f)
            screen.tap(screen.button("Retour"))
            assertTrue(backed)
        }
    }

    @Test
    fun `long phone category title does not share space with view controls`() {
        val title = "International cinema · Independent films and documentaries"
        render("category-header-390") { BrowseScene(title) }.use { screen ->
            screen.assertCategoryToolbar(title, "Grid view")
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h720dp-television-mdpi")
    fun `wide category toolbar supports independent D-pad focus and activation`() {
        val title = "Cinema, documentaries and international series for the whole family"
        var grid = false
        render("category-header-tv", width = 1280, height = 720, keyboard = true) {
            BrowseScene(title, wide = true, onToggle = { grid = true })
        }.use { screen ->
            screen.assertCategoryToolbar(title, "Grid view")
            val before = screen.button("Grid view").boundsInRoot
            screen.focus(screen.button("Grid view"))
            screen.awaitFocusRing(screen.button("Grid view"))
            screen.capture("category-header-tv-focus")
            assertEquals(before, screen.button("Grid view").boundsInRoot)
            screen.key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(grid)
        }
    }

    @Test
    fun `double sized category text keeps actions visible and separate`() {
        val title = "Independent cinema and documentaries from around the world"
        render("category-header-font-200", fontScale = 2f) { BrowseScene(title) }.use { screen ->
            screen.assertCategoryToolbar(title, "Grid view")
            assertTrue(screen.button("Grid view").boundsInRoot.bottom < 844f)
        }
    }

    @Test
    fun `compact utility header grows for long translated titles and keeps its back action reachable`() {
        val title = "Paramètres du serveur et des téléchargements"
        listOf(1f, 2f).forEach { scale ->
            var backed = false
            render(if (scale == 1f) "utility-header" else "utility-header-font-200", fontScale = scale) {
                ScreenHeader(title, onBack = { backed = true }, supportingText = listOf("Home server · Family library"))
            }.use { screen ->
                val heading = screen.nodes().single { it.config.contains(SemanticsProperties.Heading) }
                val back = screen.button("Back")
                assertTrue(heading.boundsInRoot.left >= back.boundsInRoot.right + 8f)
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(heading.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
                assertEquals(20f, layouts.single().layoutInput.style.fontSize.value, .01f)
                assertEquals(Color(0xFFF5F5F5), layouts.single().layoutInput.style.color)
                assertTrue(back.boundsInRoot.width >= 48f && back.boundsInRoot.height >= 48f)
                screen.tap(back)
                assertTrue(backed)
            }
        }
    }

    @Composable
    private fun BrowseScene(
        title: String,
        wide: Boolean = false,
        grid: Boolean = false,
        onBack: () -> Unit = {},
        onToggle: () -> Unit = {},
    ) {
        Scaffold(
            topBar = { OpenTvMasthead(true, DockSection.BROWSE, "Family library", true, {}, {}, {}, {}, wide) },
            bottomBar = { if (!wide) OpenTvDock(true, DockSection.BROWSE, {}, {}) },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 1180.dp).fillMaxSize()) {
                    BrowseHeading(title, onBack)
                    BrowseTabs(ChannelKind.MOVIE, mapOf(ChannelKind.LIVE to 20, ChannelKind.MOVIE to 120, ChannelKind.SERIES to 80)) {}
                    BrowseToolbar("120 films", grid, onToggle)
                    FilterField("", {}, "Filter this category")
                    BrowseCategories(listOf(CatalogGroup("International cinema", 120), CatalogGroup("Documentaries", 80)), null) {}
                }
            }
        }
    }

    @Composable
    private fun Scene(
        wide: Boolean = false,
        hasPlaylist: Boolean = true,
        onSection: (DockSection) -> Unit = {},
        onChoose: () -> Unit = {},
        onHome: () -> Unit = {},
    ) {
        Scaffold(
            topBar = { OpenTvMasthead(hasPlaylist, DockSection.HOME, "Family library", hasPlaylist, onHome, onChoose, {}, onSection, wide) },
            bottomBar = { if (!wide) OpenTvDock(hasPlaylist, DockSection.HOME, {}, onSection) },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                HomeLibrary(listOf(CatalogSourceEntry(SourceId.LocalPlaylist(1), "Family library", itemCount = 240)), false, 0, false, {}, {}, {}, {})
            }
        }
    }

    private fun render(
        name: String,
        width: Int = 390,
        height: Int = 844,
        dark: Boolean = true,
        keyboard: Boolean = false,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ): Screen {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        // Drive recomposition explicitly: manual measure/layout alone does not deliver frames.
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(Dispatchers.Main.immediate + clock)
        val recomposer = Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        activity.setContent(parent = recomposer) {
            if (keyboard) {
                val inputMode = LocalInputModeManager.current
                SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
            }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OpenTvDesign(dark, content)
            }
        }
        controller.visible().windowFocusChanged(true)
        val decor = activity.window.decorView
        val settle = {
            Snapshot.sendApplyNotifications()
            repeat(6) {
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
                Snapshot.sendApplyNotifications()
                clock.sendFrame(SystemClock.uptimeMillis() * 1_000_000)
                decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, width, height)
            }
        }
        settle()
        val owner = views(decor).filterIsInstance<RootForTest>().single()
        return Screen({ owner.semanticsOwner.rootSemanticsNode }, owner, owner as View, decor, settle) {
            controller.pause().stop().destroy()
            recomposer.cancel()
            scope.cancel()
        }
            .also { it.capture(name) }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(views(view.getChildAt(it))) }
    }

    private class Screen(
        private val root: () -> SemanticsNode,
        private val owner: RootForTest,
        private val view: View,
        private val decor: View,
        private val settle: () -> Unit,
        private val dispose: () -> Unit,
    ) : AutoCloseable {
        fun nodes(): Sequence<SemanticsNode> = walk(root())
        private fun walk(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            node.children.forEach { yieldAll(walk(it)) }
        }
        fun hasText(label: String) = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true }
        fun clickableText(label: String) = nodes().single {
            it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
        }
        fun button(description: String) = nodes().single {
            it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
        }
        fun assertCategoryToolbar(title: String, view: String) {
            val heading = nodes().single {
                it.config.contains(SemanticsProperties.Heading) && it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == title } == true
            }
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(heading.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
            assertFalse("Category title was truncated", layouts.any { result -> (0 until result.lineCount).any(result::isLineEllipsized) })
            button(view).let { action ->
                assertTrue(action.boundsInRoot.top >= heading.boundsInRoot.bottom)
                assertTrue(action.boundsInRoot.width >= 48f && action.boundsInRoot.height >= 48f)
                assertTrue(action.boundsInRoot.left >= 0 && action.boundsInRoot.right <= decor.width)
            }
        }
        fun destination(label: String) = nodes().single {
            it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.Role) in setOf(Role.Tab, Role.Button) &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
        }
        fun assertSpacedTargets(labels: List<String>, gap: Float) {
            val targets = labels.map(::destination)
            targets.forEach { assertTrue(it.boundsInRoot.width >= 48f && it.boundsInRoot.height >= 48f) }
            targets.zipWithNext().forEach { (left, right) -> assertTrue("Crowded $left / $right", right.boundsInRoot.left - left.boundsInRoot.right >= gap - .1f) }
        }
        fun focus(node: SemanticsNode) {
            view.requestFocus()
            assertTrue(node.config[SemanticsActions.RequestFocus].action!!.invoke())
            settle()
        }
        fun key(keyCode: Int) {
            owner.sendKeyEvent(KeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)))
            owner.sendKeyEvent(KeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)))
            settle()
        }
        fun hover(node: SemanticsNode) {
            val point = node.boundsInRoot.center
            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
            val coordinates = MotionEvent.PointerCoords().apply { x = point.x; y = point.y }
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_ENTER, 1, arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            // AndroidComposeView exposes this override publicly; View declares it protected.
            view.javaClass.getMethod("dispatchHoverEvent", MotionEvent::class.java).invoke(view, event)
            event.recycle()
            settle()
        }
        fun tap(node: SemanticsNode) {
            val point = node.boundsInRoot.center
            val now = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(now, now + action, action, point.x, point.y, 0)
                view.dispatchTouchEvent(event)
                event.recycle()
            }
            settle()
        }
        fun capture(name: String) {
            System.getenv("OPENTV_SCREENSHOT_DIR")?.let { directory ->
                val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                decor.draw(Canvas(bitmap))
                File(directory).mkdirs()
                File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        fun backgroundAt(node: SemanticsNode): Int = pixel(node.boundsInRoot.center.x.toInt(), node.boundsInRoot.top.toInt() + 6)
        fun awaitBackground(node: SemanticsNode, expected: Int) = awaitPixel(node.boundsInRoot.center.x.toInt(), node.boundsInRoot.top.toInt() + 6, expected)
        fun awaitFocusRing(node: SemanticsNode) = awaitPixel(node.boundsInRoot.center.x.toInt(), node.boundsInRoot.top.toInt() + 1, 0xff8fa8ff.toInt())
        private fun awaitPixel(x: Int, y: Int, expected: Int) {
            val deadline = System.nanoTime() + 1_000_000_000L
            while (pixel(x, y) != expected && System.nanoTime() < deadline) {
                settle()
                Thread.yield()
            }
            assertEquals("Rendered control at ($x, $y)", expected, pixel(x, y))
        }
        private fun pixel(x: Int, y: Int): Int {
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            return bitmap.getPixel(x, y).also { bitmap.recycle() }
        }
        override fun close() = dispose()
    }
}
