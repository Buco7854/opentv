package com.buco7854.opentv

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.ui.account.AccountScreen
import com.buco7854.opentv.ui.browse.BrowseScreen
import com.buco7854.opentv.ui.details.EpisodeDetailScreen
import com.buco7854.opentv.ui.details.MovieDetailScreen
import com.buco7854.opentv.ui.details.SeriesDetailScreen
import com.buco7854.opentv.ui.details.XtreamSeriesScreen
import com.buco7854.opentv.ui.diag.LogScreen
import com.buco7854.opentv.ui.downloads.DownloadsScreen
import com.buco7854.opentv.ui.favorites.FavoritesScreen
import com.buco7854.opentv.ui.home.HomeScreen
import com.buco7854.opentv.ui.player.PipController
import com.buco7854.opentv.ui.player.PlayerScreen
import com.buco7854.opentv.ui.search.SearchScreen
import com.buco7854.opentv.ui.settings.SettingsScreen
import com.buco7854.opentv.ui.shell.DockSection
import com.buco7854.opentv.ui.shell.AppShellViewModel
import com.buco7854.opentv.ui.shell.OpenTvDock
import com.buco7854.opentv.ui.shell.PlaylistsPanel
import com.buco7854.opentv.ui.theme.OpenTvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenTvTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppShell()
                }
            }
        }
    }

    // Auto-enter PiP when leaving while the player is active.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        PipController.onUserLeave?.invoke()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipController.isInPip.value = isInPictureInPictureMode
    }
}

object Routes {
    fun browse(playlistId: Long, tab: Int? = null, group: String? = null) =
        "browse/$playlistId?t=${tab ?: -1}&g=${Uri.encode(group ?: "")}"
    fun search(playlistId: Long) = "search/$playlistId"
    fun movie(channelId: Long) = "movie/$channelId"
    fun account(playlistId: Long) = "account/$playlistId"
    fun episode(channelId: Long) = "episode/$channelId"
    fun favorites(playlistId: Long) = "favorites/$playlistId"
    fun series(playlistId: Long, seriesKey: String) = "series/$playlistId/${Uri.encode(seriesKey)}"
    fun xtreamSeries(playlistId: Long, seriesId: Long) = "xseries/$playlistId/$seriesId"
    fun player(url: String, title: String, playlistId: Long = -1, tvgId: String? = null, live: Boolean = false) =
        "player?u=${Uri.encode(url)}&t=${Uri.encode(title)}&p=$playlistId&c=${Uri.encode(tvgId ?: "")}&l=$live"
    const val DOWNLOADS = "downloads"
    const val LOG = "log"
    const val SETTINGS = "settings"
    const val HOME = "home"
}

/** Dock-first shell mirroring the web client's phone layout. */
@Composable
fun AppShell(
    viewModel: AppShellViewModel = viewModel(),
) {
    val nav = rememberNavController()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val activePlaylistId = settings?.activePlaylistId ?: -1L
    var panelOpen by remember { mutableStateOf(false) }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val dockHidden = route?.startsWith("player") == true

    // Dock destinations replace the stack like tabs; details push on top.
    fun navigateSection(target: String) = nav.navigate(target) {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }

    val activeSection = when {
        route?.startsWith("browse/") == true -> {
            when (backStack?.arguments?.getInt("t")?.takeIf { it >= 0 } ?: ChannelKind.LIVE) {
                ChannelKind.MOVIE -> DockSection.MOVIES
                ChannelKind.SERIES -> DockSection.SERIES
                else -> DockSection.LIVE
            }
        }
        route?.startsWith("favorites/") == true -> DockSection.FAVORITES
        route?.startsWith("search/") == true -> DockSection.SEARCH
        else -> null
    }

    Scaffold(
        bottomBar = {
            if (!dockHidden) {
                OpenTvDock(
                    hasActivePlaylist = activePlaylistId > 0,
                    activeSection = activeSection,
                    onOpenPanel = { panelOpen = true },
                    onSection = { section ->
                        when (section) {
                            DockSection.FAVORITES -> navigateSection(Routes.favorites(activePlaylistId))
                            DockSection.SEARCH -> navigateSection(Routes.search(activePlaylistId))
                            else -> navigateSection(Routes.browse(activePlaylistId, section.tab))
                        }
                    },
                )
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            AppNav(nav, onActivePlaylist = viewModel::setActivePlaylist)
        }
    }

    if (panelOpen) {
        PlaylistsPanel(
            activePlaylistId = activePlaylistId,
            onDismiss = { panelOpen = false },
            onOpenPlaylist = {
                panelOpen = false
                viewModel.setActivePlaylist(it)
                navigateSection(Routes.browse(it))
            },
            onOpenAccount = { panelOpen = false; nav.navigate(Routes.account(it)) },
            onOpenDownloads = { panelOpen = false; nav.navigate(Routes.DOWNLOADS) },
            onOpenSettings = { panelOpen = false; nav.navigate(Routes.SETTINGS) },
            onOpenLog = { panelOpen = false; nav.navigate(Routes.LOG) },
        )
    }
}

@Composable
fun AppNav(nav: NavHostController, onActivePlaylist: (Long) -> Unit) {
    // Quick fades instead of the default slow cross-fade.
    val fadeSpec = tween<Float>(180)
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = { fadeIn(fadeSpec) },
        exitTransition = { fadeOut(fadeSpec) },
        popEnterTransition = { fadeIn(fadeSpec) },
        popExitTransition = { fadeOut(fadeSpec) },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenPlaylist = { playlistId ->
                    onActivePlaylist(playlistId)
                    nav.navigate(Routes.browse(playlistId)) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = "account/{playlistId}",
            arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
        ) { entry ->
            AccountScreen(
                playlistId = entry.arguments!!.getLong("playlistId"),
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.LOG) {
            LogScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(
            route = "browse/{playlistId}?t={t}&g={g}",
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType },
                navArgument("t") { type = NavType.IntType; defaultValue = -1 },
                navArgument("g") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val playlistId = entry.arguments!!.getLong("playlistId")
            LaunchedEffect(playlistId) { onActivePlaylist(playlistId) }
            BrowseScreen(
                playlistId = playlistId,
                initialTab = entry.arguments!!.getInt("t").takeIf { it >= 0 },
                initialGroup = entry.arguments!!.getString("g").orEmpty().ifEmpty { null },
                onPlay = { url, title, tvgId, live -> nav.navigate(Routes.player(url, title, playlistId, tvgId, live)) },
                onOpenMovie = { nav.navigate(Routes.movie(it)) },
                onOpenSeries = { nav.navigate(Routes.series(playlistId, it)) },
                onOpenXtreamSeries = { nav.navigate(Routes.xtreamSeries(playlistId, it)) },
                onOpenAccount = { nav.navigate(Routes.account(playlistId)) },
            )
        }
        composable(
            route = "favorites/{playlistId}",
            arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
        ) { entry ->
            val playlistId = entry.arguments!!.getLong("playlistId")
            FavoritesScreen(
                playlistId = playlistId,
                onBack = { nav.popBackStack() },
                onPlay = { url, title, tvgId, live -> nav.navigate(Routes.player(url, title, playlistId, tvgId, live)) },
                onOpenMovie = { nav.navigate(Routes.movie(it)) },
                onOpenSeries = { nav.navigate(Routes.series(playlistId, it)) },
                onOpenXtreamSeries = { nav.navigate(Routes.xtreamSeries(playlistId, it)) },
            )
        }
        composable(
            route = "xseries/{playlistId}/{seriesId}",
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType },
                navArgument("seriesId") { type = NavType.LongType },
            ),
        ) { entry ->
            XtreamSeriesScreen(
                playlistId = entry.arguments!!.getLong("playlistId"),
                seriesId = entry.arguments!!.getLong("seriesId"),
                onBack = { nav.popBackStack() },
                onOpenEpisode = { nav.navigate(Routes.episode(it)) },
            )
        }
        composable(
            route = "episode/{channelId}",
            arguments = listOf(navArgument("channelId") { type = NavType.LongType }),
        ) { entry ->
            EpisodeDetailScreen(
                channelId = entry.arguments!!.getLong("channelId"),
                onBack = { nav.popBackStack() },
                onPlay = { url, title -> nav.navigate(Routes.player(url, title)) },
            )
        }
        composable(
            route = "movie/{channelId}",
            arguments = listOf(navArgument("channelId") { type = NavType.LongType }),
        ) { entry ->
            MovieDetailScreen(
                channelId = entry.arguments!!.getLong("channelId"),
                onBack = { nav.popBackStack() },
                onPlay = { url, title -> nav.navigate(Routes.player(url, title)) },
            )
        }
        composable(
            route = "series/{playlistId}/{seriesKey}",
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType },
                navArgument("seriesKey") { type = NavType.StringType },
            ),
        ) { entry ->
            SeriesDetailScreen(
                playlistId = entry.arguments!!.getLong("playlistId"),
                seriesKey = entry.arguments!!.getString("seriesKey")!!,
                onBack = { nav.popBackStack() },
                onOpenEpisode = { nav.navigate(Routes.episode(it)) },
            )
        }
        composable(
            route = "search/{playlistId}",
            arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
        ) { entry ->
            val playlistId = entry.arguments!!.getLong("playlistId")
            SearchScreen(
                playlistId = playlistId,
                onBack = { nav.popBackStack() },
                onPlay = { url, title, live -> nav.navigate(Routes.player(url, title, playlistId, live = live)) },
                onOpenMovie = { nav.navigate(Routes.movie(it)) },
                onOpenSeries = { nav.navigate(Routes.series(playlistId, it)) },
                onOpenXtreamSeries = { nav.navigate(Routes.xtreamSeries(playlistId, it)) },
            )
        }
        composable(Routes.DOWNLOADS) {
            DownloadsScreen(
                onBack = { nav.popBackStack() },
                onPlay = { url, title -> nav.navigate(Routes.player(url, title)) },
            )
        }
        composable(
            route = "player?u={u}&t={t}&p={p}&c={c}&l={l}",
            arguments = listOf(
                navArgument("u") { type = NavType.StringType },
                navArgument("t") { type = NavType.StringType; defaultValue = "" },
                navArgument("p") { type = NavType.LongType; defaultValue = -1L },
                navArgument("c") { type = NavType.StringType; defaultValue = "" },
                navArgument("l") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val args = entry.arguments!!
            PlayerScreen(
                url = args.getString("u")!!,
                title = args.getString("t").orEmpty(),
                playlistId = args.getLong("p"),
                tvgId = args.getString("c").orEmpty().ifEmpty { null },
                initialLive = args.getBoolean("l"),
                onBack = { nav.popBackStack() },
            )
        }
    }
}
