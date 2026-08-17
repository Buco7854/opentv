@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.buco7854.opentv.ui.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.buco7854.opentv.diag.ErrorLog

/**
 * Showing HDR video on a screen that cannot display it.
 *
 * An HDR stream describes brightness on a curve built for a far brighter panel than a
 * plain one can produce. Handed to an ordinary screen unconverted, the picture comes out
 * very dark: everything below the highlights is compressed into near-black, and a film
 * becomes unwatchable rather than merely wrong.
 *
 * Decoding it is not the same as converting it. The decoder happily produces those
 * frames in hardware, and does so correctly; tone mapping is the separate step of
 * bringing that curve and colour space back to what the screen can show. A device can do
 * the first perfectly and still need the second.
 *
 * From Android 13 a decoder can be asked to do the conversion as it decodes, which costs
 * nothing extra because the same hardware performs it. That is the only mechanism used
 * here. On an older device the request has no effect and HDR still plays dark.
 */

/** Android 13, where a decoder may be asked to hand back SDR frames. */
private const val TONE_MAPPING_SDK = Build.VERSION_CODES.TIRAMISU

/**
 * Whether these frames must be converted before they reach this screen.
 *
 * Only when the screen cannot show the kind of HDR in hand. A phone with an HDR panel
 * displays the stream as its author intended, and converting it there would throw that
 * away to fix a problem the device does not have. Content that is already SDR, or whose
 * transfer we never learned, is left alone: guessing would darken ordinary video.
 */
internal fun shouldToneMapToSdr(colorTransfer: Int?, displayHdrTypes: Set<Int>): Boolean =
    when (colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> !displayHdrTypes.any { it in PQ_DISPLAY_TYPES }
        C.COLOR_TRANSFER_HLG -> !displayHdrTypes.any { it in HLG_DISPLAY_TYPES }
        else -> false
    }

/**
 * Which screens can show which stream, by name rather than by "supports HDR".
 *
 * A screen advertising HLG has not thereby promised PQ, and a stream graded on the one
 * curve shown on the other is the same darkness this exists to prevent. Dolby Vision
 * players decode a PQ base layer, so they can show PQ.
 */
private val PQ_DISPLAY_TYPES = setOf(
    Display.HdrCapabilities.HDR_TYPE_HDR10,
    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS,
    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION,
)

private val HLG_DISPLAY_TYPES = setOf(
    Display.HdrCapabilities.HDR_TYPE_HLG,
    Display.HdrCapabilities.HDR_TYPE_HLG_PLUS,
)

/** What the screen this app is on can actually display. Empty means an ordinary screen. */
internal fun displayHdrTypes(context: Context): Set<Int> {
    val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
        ?.getDisplay(Display.DEFAULT_DISPLAY)
        ?: return emptySet()
    @Suppress("DEPRECATION")
    return display.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty()
}

/**
 * Renderers that ask the decoder for SDR frames when this screen needs them.
 *
 * The request rides on the format the codec is configured with, so it is decided per
 * stream: an SDR film on the same device is untouched, and nothing extra runs for it.
 */
internal class ToneMappingRenderersFactory(
    context: Context,
    private val displayHdrTypes: Set<Int>,
) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        // Only the platform decoder is built, which is what the base class builds too:
        // this app ships no decoder extensions, so there is no other renderer to lose.
        out.add(
            ToneMappingVideoRenderer(
                MediaCodecVideoRenderer.Builder(context)
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
                displayHdrTypes,
            ),
        )
    }
}

private class ToneMappingVideoRenderer(
    builder: Builder,
    private val displayHdrTypes: Set<Int>,
) : MediaCodecVideoRenderer(builder) {

    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int,
    ): MediaFormat {
        val mediaFormat = super.getMediaFormat(
            format,
            codecMimeType,
            codecMaxValues,
            codecOperatingRate,
            deviceNeedsNoPostProcessWorkaround,
            tunnelingAudioSessionId,
        )
        if (Build.VERSION.SDK_INT < TONE_MAPPING_SDK) return mediaFormat
        if (!shouldToneMapToSdr(format.colorInfo?.colorTransfer, displayHdrTypes)) {
            return mediaFormat
        }
        mediaFormat.setInteger(
            MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
            MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        )
        // A decoder is free to ignore this, and one that does leaves the picture as dark
        // as before with nothing else to show for it. Recorded so that "still dark" can
        // be told apart from "we never asked".
        ErrorLog.log(
            "HDR tone mapping",
            message = "Asked the decoder for SDR frames ($codecMimeType)",
        )
        return mediaFormat
    }
}
