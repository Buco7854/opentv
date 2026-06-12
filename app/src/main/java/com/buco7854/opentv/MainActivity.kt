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
import com.buco7854.opentv.ui.browse.BrowseScreen
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
    fun browse(playlistId: Long) = "browse/$playlistId"
    fun search(playlistId: Long) = "search/$playlistId"
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
            )
        }
        composable(Routes.LOG) {
            LogScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(
            route = "browse/{playlistId}",
            arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
        ) { entry ->
            val playlistId = entry.arguments!!.getLong("playlistId")
            BrowseScreen(
                playlistId = playlistId,
                onBack = { nav.popBackStack() },
                onSearch = { nav.navigate(Routes.search(playlistId)) },
                onPlay = { url, title, tvgId -> nav.navigate(Routes.player(url, title, playlistId, tvgId)) },
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
