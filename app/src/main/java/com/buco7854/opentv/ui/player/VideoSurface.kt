package com.buco7854.opentv.ui.player

import android.content.Context
import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView

/**
 * Minimal PlayerView replacement: TextureView in an AspectRatioFrameLayout plus a SubtitleView overlay.
 *
 * TextureView (not SurfaceView) avoids the z-order artifact of the last frame lingering over the next screen.
 */
@UnstableApi
class VideoSurface(context: Context) : FrameLayout(context) {

    private val frame = AspectRatioFrameLayout(context)
    private val texture = TextureView(context)
    val subtitleView = SubtitleView(context)

    private var player: ExoPlayer? = null
    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) = applyAspectRatio(videoSize)
        override fun onCues(cueGroup: CueGroup) {
            subtitleView.setCues(cueGroup.cues)
        }
    }

    init {
        frame.addView(
            texture,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    var resizeMode: Int
        get() = frame.resizeMode
        set(value) {
            frame.resizeMode = value
        }

    fun setPlayer(newPlayer: ExoPlayer?) {
        if (player === newPlayer) return
        player?.removeListener(listener)
        player?.clearVideoTextureView(texture)
        player = newPlayer
        if (newPlayer != null) {
            newPlayer.setVideoTextureView(texture)
            newPlayer.addListener(listener)
            applyAspectRatio(newPlayer.videoSize)
            subtitleView.setCues(newPlayer.currentCues.cues)
        }
    }

    private fun applyAspectRatio(size: VideoSize) {
        val ratio =
            if (size.width == 0 || size.height == 0) 0f
            else size.width * size.pixelWidthHeightRatio / size.height
        frame.setAspectRatio(ratio)
    }
}
