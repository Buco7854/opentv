package com.buco7854.opentv.ui.player

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.data.net.Http
import com.buco7854.opentv.diag.ErrorLog
import com.buco7854.opentv.ui.theme.Mint
import java.text.DateFormat
import java.util.Date

/**
 * Full-screen playback. Hardware decoding is the default: DefaultRenderersFactory
 * uses the device's MediaCodec hardware decoders first and never falls back to
 * software extension renderers (extension mode OFF unless enabled).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    url: String,
    title: String,
    playlistId: Long,
    tvgId: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var error by remember { mutableStateOf<String?>(null) }
    var nowNext by remember { mutableStateOf<Pair<String, String?>?>(null) }

    val player = remember(url) {
        val httpFactory = OkHttpDataSource.Factory(Http.ok).setUserAgent(Http.USER_AGENT)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        ExoPlayer.Builder(context, DefaultRenderersFactory(context))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(playbackError: PlaybackException) {
                ErrorLog.log("Playback: $title", playbackError)
                val cause = playbackError.cause?.message?.let { ": ${ErrorLog.redact(it)}" } ?: ""
                error = playbackError.errorCodeName + cause
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Immersive fullscreen while the player is open.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // EPG overlay data comes from the local database only - no network here.
    LaunchedEffect(tvgId) {
        if (tvgId != null && playlistId > 0) {
            val programmes = OpenTvApp.graph.epg.upcoming(playlistId, tvgId, limit = 2)
            val now = programmes.firstOrNull()
            if (now != null) {
                val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
                val nowLabel = "${now.title} (until ${timeFormat.format(Date(now.endMs))})"
                val nextLabel = programmes.getOrNull(1)?.let { "Next: ${it.title}" }
                nowNext = nowLabel to nextLabel
            }
        }
    }

    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))
                )
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
            nowNext?.let { (now, next) ->
                Text(now, style = MaterialTheme.typography.bodySmall, color = Mint)
                next?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                }
            }
            error?.let {
                Text(
                    "Playback error: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
