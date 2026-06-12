package com.buco7854.opentv

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.buco7854.opentv.ui.account.AccountScreen
import com.buco7854.opentv.ui.browse.BrowseScreen
import com.buco7854.opentv.ui.details.EpisodeDetailScreen
import com.buco7854.opentv.ui.details.MovieDetailScreen
import com.buco7854.opentv.ui.details.SeriesDetailScreen
import com.buco7854.opentv.ui.details.XtreamSeriesScreen
import com.buco7854.opentv.ui.diag.LogScreen
import com.buco7854.opentv.ui.downloads.DownloadsScreen
import com.buco7854.opentv.ui.home.HomeScreen
import com.buco7854.opentv.ui.player.PlayerScreen
import com.buco7854.opentv.ui.search.SearchScreen
import com.buco7854.opentv.ui.settings.SettingsScreen
import com.buco7854.opentv.ui.theme.OpenTvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenTvTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav()
                }
            }
        }
    }
}

object Routes {
    fun browse(playlistId: Long, tab: Int? = null, group: String? = null) =
        "browse/$playlistId?t=${tab ?: -1}&g=${Uri.encode(group ?: "")}"
    fun search(playlistId: Long) = "search/$playlistId"
    fun movie(channelId: Long) = "movie/$channelId"
    fun account(playlistId: Long) = "account/$playlistId"
    fun episode(channelId: Long) = "episode/$channelId"
    fun series(playlistId: Long, seriesKey: String) = "series/$playlistId/${Uri.encode(seriesKey)}"
    fun xtreamSeries(playlistId: Long, seriesId: Long) = "xseries/$playlistId/$seriesId"
    fun player(url: String, title: String, playlistId: Long = -1, tvgId: String? = null) =
        "player?u=${Uri.encode(url)}&t=${Uri.encode(title)}&p=$playlistId&c=${Uri.encode(tvgId ?: "")}"
    const val DOWNLOADS = "downloads"
    const val LOG = "log"
    const val SETTINGS = "settings"
    const val HOME = "home"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenPlaylist = { nav.navigate(Routes.browse(it)) },
                onOpenDownloads = { nav.navigate(Routes.DOWNLOADS) },
                onOpenLog = { nav.navigate(Routes.LOG) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenAccount = { nav.navigate(Routes.account(it)) },
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
            BrowseScreen(
                playlistId = playlistId,
                initialTab = entry.arguments!!.getInt("t").takeIf { it >= 0 },
                initialGroup = entry.arguments!!.getString("g").orEmpty().ifEmpty { null },
                onBack = { nav.popBackStack() },
                onSearch = { nav.navigate(Routes.search(playlistId)) },
                onPlay = { url, title, tvgId -> nav.navigate(Routes.player(url, title, playlistId, tvgId)) },
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
                onPlay = { url, title -> nav.navigate(Routes.player(url, title, playlistId)) },
                onOpenMovie = { nav.navigate(Routes.movie(it)) },
                onOpenSeries = { nav.navigate(Routes.series(playlistId, it)) },
                onOpenXtreamSeries = { nav.navigate(Routes.xtreamSeries(playlistId, it)) },
                onOpenCategory = { kind, group ->
                    nav.navigate(Routes.browse(playlistId, kind, group))
                },
            )
        }
        composable(Routes.DOWNLOADS) {
            DownloadsScreen(
                onBack = { nav.popBackStack() },
                onPlay = { url, title -> nav.navigate(Routes.player(url, title)) },
            )
        }
        composable(
            route = "player?u={u}&t={t}&p={p}&c={c}",
            arguments = listOf(
                navArgument("u") { type = NavType.StringType },
                navArgument("t") { type = NavType.StringType; defaultValue = "" },
                navArgument("p") { type = NavType.LongType; defaultValue = -1L },
                navArgument("c") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val args = entry.arguments!!
            PlayerScreen(
                url = args.getString("u")!!,
                title = args.getString("t").orEmpty(),
                playlistId = args.getLong("p"),
                tvgId = args.getString("c").orEmpty().ifEmpty { null },
                onBack = { nav.popBackStack() },
            )
        }
    }
}
