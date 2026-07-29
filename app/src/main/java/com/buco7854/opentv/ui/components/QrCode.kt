package com.buco7854.opentv.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR render of [content] on a white tile, readable from a couch when shown on
 * a TV. Always black-on-white regardless of theme: scanners want contrast.
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    contentDescription: String? = null,
) {
    val bitmap = remember(content) { qrBitmap(content) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        // FilterQuality default bilinear would blur the modules; the bitmap is
        // rendered at module resolution and scaled by the layout instead.
        filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
        modifier = modifier
            .size(size)
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(12.dp),
    )
}

private fun qrBitmap(content: String): Bitmap {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
        ),
    )
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        val x = i % matrix.width
        val y = i / matrix.width
        if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.RGB_565)
}
