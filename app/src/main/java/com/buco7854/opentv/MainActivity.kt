package com.buco7854.opentv

import android.app.Application
import android.net.Uri
import android.content.Intent
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.source.CatalogItem
import com.buco7854.opentv.source.ContentRef
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.source.decode
import com.buco7854.opentv.source.encode
import com.buco7854.opentv.ui.account.AccountScreen
import com.buco7854.opentv.ui.browse.BrowseScreen
import com.buco7854.opentv.ui.details.EpisodeDetailScreen
import com.buco7854.opentv.ui.details.MovieDetailScreen
import com.buco7854.opentv.ui.details.SeriesDetailScreen
import com.buco7854.opentv.ui.details.XtreamSeriesScreen
import com.buco7854.opentv.ui.diag.LogScreen
import com.buco7854.opentv.ui.downloads.DownloadsScreen
import com.buco7854.opentv.ui.favorites.AllFavoritesScreen
import com.buco7854.opentv.ui.home.HomeScreen
import com.buco7854.opentv.ui.hub.HubSettingsScreen
import com.buco7854.opentv.ui.hub.HubSignInScreen
import com.buco7854.opentv.ui.player.PipController
import com.buco7854.opentv.ui.player.PlayerScreen
import com.buco7854.opentv.ui.player.PlayerTarget
import com.buco7854.opentv.ui.player.decode
import com.buco7854.opentv.ui.player.encode
import com.buco7854.opentv.ui.search.SearchScreen
import com.buco7854.opentv.ui.settings.SettingsScreen
import com.buco7854.opentv.ui.shell.DockSection
import com.buco7854.opentv.ui.shell.AppShellViewModel
import com.buco7854.opentv.ui.shell.OpenTvDock
import com.buco7854.opentv.ui.shell.PlaylistsPanel
import com.buco7854.opentv.hub.BrowserSignInReturn
import com.buco7854.opentv.ui.theme.OpenTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The browser can return here after a sign-in. singleTop keeps that arriving as a
        // new intent on this instance rather than stacking a second copy of the app.
        if (BrowserSignInReturn.isSignInReturn(intent)) BrowserSignInReturn.signal()
        enableEdgeToEdge()
        setContent {
            OpenTvTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppShell()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (BrowserSignInReturn.isSignInReturn(intent)) BrowserSignInReturn.signal()
    }

    override fun onStart() {
        super.onStart()
        OpenTvApp.graph.downloads.setForeground(true)
    }

    override fun onStop() {
        OpenTvApp.graph.downloads.setForeground(false)
        super.onStop()
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
    fun browse(sourceId: SourceId, tab: Int? = null, group: String? = null) =
        "browse/${Uri.encode(sourceId.encode())}?t=${tab ?: -1}&g=${Uri.encode(group ?: "")}"
    fun search(sourceId: SourceId) = "search/${Uri.encode(sourceId.encode())}"
    fun movie(sourceId: SourceId, ref: ContentRef) =
        "movie/${Uri.encode(sourceId.encode())}/${Uri.encode(ref.encode())}"
    fun account(source: SourceId) = "account/${Uri.encode(source.encode())}"
    fun episode(sourceId: SourceId, ref: ContentRef) =
        "episode/${Uri.encode(sourceId.encode())}/${Uri.encode(ref.encode())}"
    const val ALL_FAVORITES = "favorites"
    fun series(sourceId: SourceId, item: CatalogItem) =
        "series/${Uri.encode(sourceId.encode())}/${Uri.encode(item.ref.encode())}" +
            "?k=${Uri.encode(item.seriesKey ?: item.title)}"
    fun xtreamSeries(sourceId: SourceId, item: CatalogItem) =
        "xseries/${Uri.encode(sourceId.encode())}/${Uri.encode(item.ref.encode())}" +
            "?k=${Uri.encode(item.seriesKey ?: item.title)}&i=${Uri.encode(item.seriesId.orEmpty())}"
    fun player(
        url: String,
        title: String,
        playlistId: Long = -1,
        tvgId: String? = null,
        live: Boolean = false,
    ) = player(PlayerTarget.LocalUrl(url, title, playlistId, tvgId, live))

    fun player(target: PlayerTarget): String {
        val local = target as? PlayerTarget.LocalUrl
        return "player?u=${Uri.encode(local?.url.orEmpty())}" +
            "&t=${Uri.encode(target.title)}" +
            "&p=${local?.playlistId ?: -1L}" +
            "&c=${Uri.encode(local?.tvgId.orEmpty())}" +
            "&l=${target.live}" +
            "&x=${Uri.encode(target.encode())}"
    }
    fun hubSettings(hubId: Long) = "hub/$hubId"
    const val DOWNLOADS = "downloads"
    const val LOG = "log"
    const val SETTINGS = "settings"
    const val HOME = "home"
    const val HUB_SIGN_IN = "hub/connect"
    const val HUB_SIGN_IN_ROUTE = "hub/connect?hubId={hubId}"
    fun hubSignIn(hubId: Long?) =
        hubId?.let { "$HUB_SIGN_IN?hubId=$it" } ?: HUB_SIGN_IN
}

/** Reauthentication resumes its caller; only adding a new connection opens its settings. */
internal fun destinationAfterHubSignIn(
    reauthenticatedHubId: Long?,
    completedHubId: Long,
): String? = if (reauthenticatedHubId == null) Routes.hubSettings(completedHubId) else null

internal data class CatalogSourceAvailability(
    val localPlaylistIds: List<Long>,
    val signedInHubIds: Set<Long>,
) {
    fun contains(source: SourceId): Boolean = when (source) {
        is SourceId.LocalPlaylist -> source.playlistId in localPlaylistIds
        is SourceId.Hub -> source.hubId in signedInHubIds
        is SourceId.HubConnection -> false
    }

    val fallback: SourceId?
        get() = localPlaylistIds.firstOrNull()?.let(SourceId::LocalPlaylist)
}

internal class CatalogSourceAvailabilityViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = OpenTvApp.graph

    val availability = combine(
        graph.playlists.playlists,
        graph.hubAccounts.sources,
    ) { playlists, hubs ->
        // Vault decryption can consult Android Keystore. Keep it off the UI thread while the
        // shell decides whether a remembered hub still has a usable local session.
        withContext(Dispatchers.IO) {
            CatalogSourceAvailability(
                localPlaylistIds = playlists.map { it.id },
                // Whether a session exists, not whether it decrypts right now. A
                // Keystore that declines once would otherwise drop the server out of
                // the shell, taking its playlists and every route that reaches them,
                // with nothing on screen to say why. An unreadable session still fails
                // its next call, and that surfaces as signed out with a way back in.
                signedInHubIds = hubs.mapNotNullTo(mutableSetOf()) { hub ->
                    hub.id.takeIf { graph.hubVault.hasStoredSession(hub.id) }
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Dock-first shell mirroring the web client's phone layout. */
@Composable
fun AppShell(
    viewModel: AppShellViewModel = viewModel(),
) {
    val nav = rememberNavController()
    val availabilityViewModel: CatalogSourceAvailabilityViewModel = viewModel()
    val sourceAvailability by
        availabilityViewModel.availability.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val activePlaylistId = settings?.activePlaylistId ?: -1L
    var panelOpen by remember { mutableStateOf(false) }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val routeSource = backStack?.arguments?.getString("source")
        ?.let(SourceId::decode)
    var rememberedSource by rememberSaveable { mutableStateOf<String?>(null) }

    // Dock destinations replace the stack like tabs; details push on top.
    fun navigateSection(target: String) = nav.navigate(target) {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }

    LaunchedEffect(routeSource, sourceAvailability) {
        val availability = sourceAvailability ?: return@LaunchedEffect
        routeSource
            ?.takeIf(availability::contains)
            ?.let { rememberedSource = it.encode() }
    }
    val activeSource = activeCatalogSource(
        routeSource,
        rememberedSource,
        activePlaylistId,
        sourceAvailability,
    )
    LaunchedEffect(routeSource, sourceAvailability, activeSource) {
        val availability = sourceAvailability ?: return@LaunchedEffect
        if (routeSource == null || availability.contains(routeSource)) return@LaunchedEffect
        rememberedSource = activeSource?.encode()
        navigateSection(activeSource?.let(Routes::browse) ?: Routes.HOME)
    }
    LaunchedEffect(activePlaylistId, sourceAvailability) {
        val availability = sourceAvailability ?: return@LaunchedEffect
        if (activePlaylistId > 0 && activePlaylistId !in availability.localPlaylistIds) {
            viewModel.setActivePlaylist(availability.localPlaylistIds.firstOrNull() ?: -1L)
        }
    }
    val dockHidden = route?.startsWith("player") == true

    val activeSection = when {
        route?.startsWith("browse/") == true -> {
            when (backStack?.arguments?.getInt("t")?.takeIf { it >= 0 } ?: ChannelKind.LIVE) {
                ChannelKind.MOVIE -> DockSection.MOVIES
                ChannelKind.SERIES -> DockSection.SERIES
                else -> DockSection.LIVE
            }
        }
        route == Routes.ALL_FAVORITES -> DockSection.FAVORITES
        route?.startsWith("search/") == true -> DockSection.SEARCH
        else -> null
    }

    Scaffold(
        bottomBar = {
            if (!dockHidden) {
                OpenTvDock(
                    hasActivePlaylist = activeSource != null,
                    activeSection = activeSection,
                    onOpenPanel = { panelOpen = true },
                    onSection = { section ->
                        when (section) {
                            DockSection.FAVORITES -> navigateSection(Routes.ALL_FAVORITES)
                            DockSection.SEARCH -> activeSource?.let {
                                navigateSection(Routes.search(it))
                            }
                            else -> activeSource?.let {
                                navigateSection(Routes.browse(it, section.tab))
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            AppNav(
                nav,
                onActivePlaylist = viewModel::setActivePlaylist,
                sourceAvailability = sourceAvailability,
            )
        }
    }

    if (panelOpen) {
        PlaylistsPanel(
            activeSourceId = activeSource,
            onDismiss = { panelOpen = false },
            onOpenSource = {
                panelOpen = false
                if (it is SourceId.LocalPlaylist) {
                    viewModel.setActivePlaylist(it.playlistId)
                }
                navigateSection(Routes.browse(it))
            },
            onOpenAccount = { panelOpen = false; nav.navigate(Routes.account(it)) },
            onOpenDownloads = { panelOpen = false; nav.navigate(Routes.DOWNLOADS) },
            onOpenSettings = { panelOpen = false; nav.navigate(Routes.SETTINGS) },
            onOpenLog = { panelOpen = false; nav.navigate(Routes.LOG) },
            onConnectHub = { panelOpen = false; nav.navigate(Routes.HUB_SIGN_IN) },
            onOpenHub = { panelOpen = false; nav.navigate(Routes.hubSettings(it)) },
            onSignInHub = { panelOpen = false; nav.navigate(Routes.hubSignIn(it)) },
        )
    }
}

internal fun activeCatalogSource(
    routeSource: SourceId?,
    rememberedSource: String?,
    activePlaylistId: Long,
    availability: CatalogSourceAvailability?,
): SourceId? {
    availability ?: return null
    return listOfNotNull(
        routeSource,
        rememberedSource?.let(SourceId::decode),
        activePlaylistId.takeIf { it > 0 }?.let(SourceId::LocalPlaylist),
    ).firstOrNull(availability::contains) ?: availability.fallback
}

@Composable
internal fun AppNav(
    nav: NavHostController,
    onActivePlaylist: (Long) -> Unit,
    sourceAvailability: CatalogSourceAvailability?,
) {
    // Quick fades instead of the default slow cross-fade.
    val fadeSpec = tween<Float>(180)

    fun targetFor(
        sourceId: SourceId,
        item: CatalogItem,
        live: Boolean,
        localPlaylistId: Long = -1L,
        localTvgId: String? = null,
    ): PlayerTarget? = when (val ref = item.ref) {
        is ContentRef.LocalUrl -> PlayerTarget.LocalUrl(
            url = ref.url,
            title = item.title,
            playlistId = localPlaylistId,
            tvgId = localTvgId,
            live = live,
        )
        is ContentRef.HubContent -> {
            val hub = sourceId as? SourceId.Hub ?: return null
            PlayerTarget.HubContent(
                hubId = hub.hubId,
                playlistId = hub.playlistId,
                contentId = ref.contentId,
                title = item.title,
                live = live,
            )
        }
    }

    fun signInRoute(source: SourceId): String = Routes.hubSignIn(
        when (source) {
            is SourceId.Hub -> source.hubId
            is SourceId.HubConnection -> source.hubId
            is SourceId.LocalPlaylist -> null
        },
    )

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
                onOpenSource = { source ->
                    if (source is SourceId.LocalPlaylist) {
                        onActivePlaylist(source.playlistId)
                    }
                    nav.navigate(Routes.browse(source)) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
                onConnectHub = { nav.navigate(Routes.HUB_SIGN_IN) },
            )
        }
        composable(
            route = "account/{source}",
            arguments = listOf(navArgument("source") { type = NavType.StringType }),
        ) { entry ->
            val source = entry.arguments!!.getString("source")?.let(SourceId::decode)
            if (source == null) {
                nav.popBackStack()
                return@composable
            }
            if (sourceAvailability?.contains(source) != true) return@composable
            AccountScreen(source = source, onBack = { nav.popBackStack() })
        }
        // Registered before "hub/{hubId}" so the literal path wins the match.
        composable(
            route = Routes.HUB_SIGN_IN_ROUTE,
            arguments = listOf(
                navArgument("hubId") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) { entry ->
            val hubId = entry.arguments!!.getLong("hubId").takeIf { it > 0 }
            HubSignInScreen(
                hubId = hubId,
                onDone = { completedHubId ->
                    nav.popBackStack()
                    destinationAfterHubSignIn(hubId, completedHubId)?.let(nav::navigate)
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = "hub/{hubId}",
            arguments = listOf(navArgument("hubId") { type = NavType.LongType }),
        ) { entry ->
            HubSettingsScreen(
                hubId = entry.arguments!!.getLong("hubId"),
                onBack = { nav.popBackStack() },
                onRemoved = { nav.popBackStack() },
                onSignIn = {
                    nav.navigate(Routes.hubSignIn(entry.arguments!!.getLong("hubId")))
                },
            )
        }
        composable(Routes.LOG) {
            LogScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(
            route = "browse/{source}?t={t}&g={g}",
            arguments = listOf(
                navArgument("source") { type = NavType.StringType },
                navArgument("t") { type = NavType.IntType; defaultValue = -1 },
                navArgument("g") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val sourceId = entry.arguments!!.getString("source")
                ?.let(SourceId::decode)
                ?: return@composable
            if (sourceAvailability?.contains(sourceId) != true) return@composable
            if (sourceId is SourceId.LocalPlaylist) {
                LaunchedEffect(sourceId.playlistId) {
                    onActivePlaylist(sourceId.playlistId)
                }
            }
            BrowseScreen(
                sourceId = sourceId,
                initialTab = entry.arguments!!.getInt("t").takeIf { it >= 0 },
                initialGroup = entry.arguments!!.getString("g").orEmpty().ifEmpty { null },
                onPlay = { item, live ->
                    val playlistId = (sourceId as? SourceId.LocalPlaylist)?.playlistId ?: -1L
                    targetFor(sourceId, item, live, playlistId, item.tvgId)
                        ?.let { nav.navigate(Routes.player(it)) }
                },
                onPlayHubCatchup = { item, programme ->
                    val hub = sourceId as? SourceId.Hub ?: return@BrowseScreen
                    val content = item.ref as? ContentRef.HubContent ?: return@BrowseScreen
                    nav.navigate(
                        Routes.player(
                            PlayerTarget.HubCatchUp(
                                hubId = hub.hubId,
                                playlistId = hub.playlistId,
                                contentId = content.contentId,
                                title = "${item.title} · ${programme.title}",
                                startMs = programme.startMs,
                                durationMs = (programme.endMs - programme.startMs).coerceAtLeast(0),
                            ),
                        ),
                    )
                },
                onOpenMovie = { nav.navigate(Routes.movie(sourceId, it)) },
                onOpenSeries = { nav.navigate(Routes.series(sourceId, it)) },
                onOpenXtreamSeries = {
                    nav.navigate(Routes.xtreamSeries(sourceId, it))
                },
                onOpenAccount = {
                    when (sourceId) {
                        is SourceId.LocalPlaylist -> nav.navigate(Routes.account(sourceId))
                        is SourceId.Hub -> nav.navigate(Routes.hubSettings(sourceId.hubId))
                        is SourceId.HubConnection -> Unit
                    }
                },
                onSignIn = { nav.navigate(signInRoute(sourceId)) },
            )
        }
        composable(Routes.ALL_FAVORITES) {
            AllFavoritesScreen(
                onOpen = { source, item ->
                    when (item.kind) {
                        ChannelKind.MOVIE -> nav.navigate(Routes.movie(source, item.ref))
                        ChannelKind.SERIES ->
                            if (item.seriesId != null) nav.navigate(Routes.xtreamSeries(source, item))
                            else nav.navigate(Routes.series(source, item))
                        else -> targetFor(
                            sourceId = source,
                            item = item,
                            live = true,
                            localPlaylistId = (source as? SourceId.LocalPlaylist)?.playlistId ?: -1L,
                            localTvgId = item.tvgId,
                        )?.let { nav.navigate(Routes.player(it)) }
                    }
                },
                onPlay = { source, item, live ->
                    targetFor(
                        sourceId = source,
                        item = item,
                        live = live,
                        localPlaylistId =
                            (source as? SourceId.LocalPlaylist)?.playlistId ?: -1L,
                        localTvgId = item.tvgId,
                    )?.let { nav.navigate(Routes.player(it)) }
                },
                onPlayHubCatchup = { source, item, programme ->
                    val hub = source as? SourceId.Hub ?: return@AllFavoritesScreen
                    val content =
                        item.ref as? ContentRef.HubContent ?: return@AllFavoritesScreen
                    nav.navigate(
                        Routes.player(
                            PlayerTarget.HubCatchUp(
                                hubId = hub.hubId,
                                playlistId = hub.playlistId,
                                contentId = content.contentId,
                                title = "${item.title} · ${programme.title}",
                                startMs = programme.startMs,
                                durationMs =
                                    (programme.endMs - programme.startMs).coerceAtLeast(0),
                            ),
                        ),
                    )
                },
                onSignIn = { source -> nav.navigate(signInRoute(source)) },
            )
        }
        composable(
            route = "xseries/{source}/{content}?k={k}&i={i}",
            arguments = listOf(
                navArgument("source") { type = NavType.StringType },
                navArgument("content") { type = NavType.StringType },
                navArgument("k") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("i") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val sourceId = entry.arguments!!.getString("source")
                ?.let(SourceId::decode)
                ?: return@composable
            if (sourceAvailability?.contains(sourceId) != true) return@composable
            val ref = entry.arguments!!.getString("content")
                ?.let(ContentRef::decode)
                ?: return@composable
            XtreamSeriesScreen(
                sourceId = sourceId,
                ref = ref,
                seriesKey = entry.arguments!!.getString("k").orEmpty().ifEmpty { null },
                seriesId = entry.arguments!!.getString("i").orEmpty().ifEmpty { null },
                onBack = { nav.popBackStack() },
                onOpenEpisode = { nav.navigate(Routes.episode(sourceId, it)) },
                onSignIn = { nav.navigate(signInRoute(sourceId)) },
            )
        }
        composable(
            route = "episode/{source}/{content}",
            arguments = listOf(
                navArgument("source") { type = NavType.StringType },
                navArgument("content") { type = NavType.StringType },
            ),
        ) { entry ->
            val sourceId = entry.arguments!!.getString("source")
                ?.let(SourceId::decode)
                ?: return@composable
            if (sourceAvailability?.contains(sourceId) != true) return@composable
            val ref = entry.arguments!!.getString("content")
                ?.let(ContentRef::decode)
                ?: return@composable
            EpisodeDetailScreen(
                sourceId = sourceId,
                ref = ref,
                onBack = { nav.popBackStack() },
                onPlay = { item ->
                    targetFor(sourceId, item, live = false)
                        ?.let { nav.navigate(Routes.player(it)) }
                },
                onSignIn = { nav.navigate(signInRoute(sourceId)) },
            )
        }
        composable(
            route = "movie/{source}/{content}",
            arguments = listOf(
                navArgument("source") { type = NavType.StringType },
                navArgument("content") { type = NavType.StringType },
            ),
        ) { entry ->
            val sourceId = entry.arguments!!.getString("source")
                ?.let(SourceId::decode)
                ?: return@composable
            if (sourceAvailability?.contains(sourceId) != true) return@composable
            val ref = entry.arguments!!.getString("content")
                ?.let(ContentRef::decode)
                ?: return@composable
            MovieDetailScreen(
                sourceId = sourceId,
                ref = ref,
                onBack = { nav.popBackStack() },
                onPlay = { item ->
                    targetFor(sourceId, item, live = false)
                        ?.let { nav.navigate(Routes.player(it)) }
                },
                onSignIn = { nav.navigate(signInRoute(sourceId)) },
            )
        }
        composable(
            route = "series/{source}/{content}?k={k}",
            arguments = listOf(
                navArgument("source") { type = NavType.StringType },
                navArgument("content") { type = NavType.StringType },
                navArgument("k") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val sourceId = entry.arguments!!.getString("source")
                ?.let(SourceId::decode)
                ?: return@composable
            if (sourceAvailability?.contains(sourceId) != true) return@composable
            val ref = entry.arguments!!.getString("content")
                ?.let(ContentRef::decode)
                ?: return@composable
            SeriesDetailScreen(
                sourceId = sourceId,
                ref = ref,
                seriesKey = entry.arguments!!.getString("k").orEmpty().ifEmpty { null },
                onBack = { nav.popBackStack() },
                onOpenEpisode = { nav.navigate(Routes.episode(sourceId, it)) },
                onSignIn = { nav.navigate(signInRoute(sourceId)) },
            )
        }
        composable(
            route = "search/{source}",
            arguments = listOf(navArgument("source") { type = NavType.StringType }),
        ) { entry ->
            val sourceId = entry.arguments!!.getString("source")
                ?.let(SourceId::decode)
                ?: return@composable
            if (sourceAvailability?.contains(sourceId) != true) return@composable
            SearchScreen(
                sourceId = sourceId,
                onBack = { nav.popBackStack() },
                onPlay = { item, live ->
                    val playlistId = (sourceId as? SourceId.LocalPlaylist)?.playlistId ?: -1L
                    targetFor(sourceId, item, live, playlistId)
                        ?.let { nav.navigate(Routes.player(it)) }
                },
                onPlayHubCatchup = { item, programme ->
                    val hub = sourceId as? SourceId.Hub ?: return@SearchScreen
                    val content = item.ref as? ContentRef.HubContent ?: return@SearchScreen
                    nav.navigate(
                        Routes.player(
                            PlayerTarget.HubCatchUp(
                                hubId = hub.hubId,
                                playlistId = hub.playlistId,
                                contentId = content.contentId,
                                title = "${item.title} · ${programme.title}",
                                startMs = programme.startMs,
                                durationMs = (programme.endMs - programme.startMs).coerceAtLeast(0),
                            ),
                        ),
                    )
                },
                onOpenMovie = { nav.navigate(Routes.movie(sourceId, it)) },
                onOpenSeries = { nav.navigate(Routes.series(sourceId, it)) },
                onOpenXtreamSeries = {
                    nav.navigate(Routes.xtreamSeries(sourceId, it))
                },
                onSignIn = { nav.navigate(signInRoute(sourceId)) },
            )
        }
        composable(Routes.DOWNLOADS) {
            DownloadsScreen(
                onBack = { nav.popBackStack() },
                onPlay = { url, title -> nav.navigate(Routes.player(url, title)) },
            )
        }
        composable(
            route = "player?u={u}&t={t}&p={p}&c={c}&l={l}&x={x}",
            arguments = listOf(
                navArgument("u") { type = NavType.StringType; defaultValue = "" },
                navArgument("t") { type = NavType.StringType; defaultValue = "" },
                navArgument("p") { type = NavType.LongType; defaultValue = -1L },
                navArgument("c") { type = NavType.StringType; defaultValue = "" },
                navArgument("l") { type = NavType.BoolType; defaultValue = false },
                navArgument("x") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val args = entry.arguments!!
            val target = args.getString("x")
                .orEmpty()
                .takeIf(String::isNotEmpty)
                ?.let(PlayerTarget::decode)
                ?: PlayerTarget.LocalUrl(
                    url = args.getString("u").orEmpty(),
                    title = args.getString("t").orEmpty(),
                    playlistId = args.getLong("p"),
                    tvgId = args.getString("c").orEmpty().ifEmpty { null },
                    live = args.getBoolean("l"),
                )
            PlayerScreen(
                target = target,
                onBack = { nav.popBackStack() },
                onSignIn = {
                    val hubId = when (target) {
                        is PlayerTarget.HubContent -> target.hubId
                        is PlayerTarget.HubCatchUp -> target.hubId
                        is PlayerTarget.LocalUrl -> null
                    }
                    nav.navigate(Routes.hubSignIn(hubId))
                },
                onPlayTarget = {
                    nav.navigate(Routes.player(it)) {
                        popUpTo(entry.destination.id) { inclusive = true }
                    }
                },
            )
        }
    }
}
