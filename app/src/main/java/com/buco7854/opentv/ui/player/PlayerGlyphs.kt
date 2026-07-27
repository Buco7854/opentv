package com.buco7854.opentv.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Lucide glyphs mirroring webapp/src/components/Icons.tsx so both players match. */
object PlayerGlyphs {
    val Back = stroked("back", "m15 18-6-6 6-6")
    val Close = stroked("close", "M18 6 6 18", "m6 6 12 12")
    val Calendar = stroked(
        "calendar",
        "M8 2v4", "M16 2v4",
        "M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
        "M3 10h18",
        "M8 14h.01", "M12 14h.01", "M16 14h.01", "M8 18h.01", "M12 18h.01", "M16 18h.01",
    )
    val Replay = stroked("replay", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5")
    val Forward = stroked("forward", "M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8", "M21 3v5h-5")
    val Play = solid("play", "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z")
    val Pause = solid(
        "pause",
        "M15 3h3a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
        "M6 3h3a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
    )
    val Audio = stroked("audio", "M2 10v3", "M6 6v11", "M10 3v18", "M14 8v7", "M18 5v13", "M22 10v3")
    val Subtitles = stroked(
        "subtitles",
        "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z",
        "M7 15h4M15 15h2M7 11h2M13 11h4",
    )
    val Speed = stroked("speed", "m12 14 4-4", "M3.34 19a10 10 0 1 1 17.32 0")
    val Aspect = stroked(
        "aspect",
        "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
        "M12 9v11",
        "M2 9h13a2 2 0 0 1 2 2v9",
    )
    val Pip = stroked(
        "pip",
        "M21 9V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v10c0 1.1.9 2 2 2h4",
        "M14 13h6a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-6a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z",
    )
}

private fun stroked(name: String, vararg paths: String) = glyph(name, paths, solidFill = false)

private fun solid(name: String, vararg paths: String) = glyph(name, paths, solidFill = true)

private fun glyph(name: String, paths: Array<out String>, solidFill: Boolean): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        for (data in paths) {
            val nodes = PathParser().parsePathString(data).toNodes()
            if (solidFill) {
                addPath(pathData = nodes, fill = SolidColor(Color.White))
            } else {
                addPath(
                    pathData = nodes,
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
    }.build()
