package com.buco7854.opentv.ui.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.drawable.Icon
import android.util.Rational
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import com.buco7854.opentv.R

private const val PIP_ACTION_TOGGLE = "com.buco7854.opentv.player.PIP_TOGGLE"

internal class PlayerSystemController(
    private val context: Context,
    private val activity: Activity?,
    private val player: Player,
    val pipSupported: Boolean,
) {
    fun enterPictureInPicture() {
        val host = activity ?: return
        if (!pipSupported) return
        runCatching { host.enterPictureInPictureMode(pictureInPictureParams()) }
    }

    fun refreshPictureInPicture() {
        val host = activity ?: return
        if (!pipSupported) return
        runCatching { host.setPictureInPictureParams(pictureInPictureParams()) }
    }

    fun toggleOrientation(isLandscape: Boolean) {
        activity?.requestedOrientation =
            if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    fun resetOrientation() {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun pictureInPictureParams(): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .apply {
                pipAspectRatio(player.videoSize.width, player.videoSize.height)?.let { ratio ->
                    setAspectRatio(Rational((ratio * 1_000).toInt(), 1_000))
                }
                setActions(listOf(playPauseAction(context, player.isPlaying)))
            }
            .build()
}

@Composable
internal fun rememberPlayerSystemController(player: Player): PlayerSystemController {
    val context = LocalContext.current
    val pipSupported = remember(context) {
        context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE,
        )
    }
    return remember(context, player, pipSupported) {
        PlayerSystemController(
            context = context,
            activity = context as? Activity,
            player = player,
            pipSupported = pipSupported,
        )
    }
}

@Composable
internal fun PlayerSystemEffects(
    session: PlayerSession,
    controller: PlayerSystemController,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(session, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> session.onHostStopped()
                Lifecycle.Event.ON_START -> session.onHostStarted()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(session.player, controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (PipController.isInPip.value) controller.refreshPictureInPicture()
            }
        }
        session.player.addListener(listener)
        onDispose { session.player.removeListener(listener) }
    }

    DisposableEffect(controller, controller.pipSupported) {
        if (controller.pipSupported) {
            PipController.onUserLeave = controller::enterPictureInPicture
        }
        onDispose { PipController.onUserLeave = null }
    }

    DisposableEffect(session.player) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action == PIP_ACTION_TOGGLE) session.togglePlayback()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PIP_ACTION_TOGGLE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    DisposableEffect(controller) {
        val activity = context as? Activity
        val window = activity?.window
        val insetsController = window?.let { WindowInsetsControllerCompat(it, view) }
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            controller.resetOrientation()
        }
    }
}

private fun playPauseAction(context: Context, isPlaying: Boolean): RemoteAction {
    val icon = Icon.createWithResource(
        context,
        if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
    )
    val label = context.getString(if (isPlaying) R.string.common_pause else R.string.common_play)
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(PIP_ACTION_TOGGLE).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return RemoteAction(icon, label, label, pendingIntent)
}
