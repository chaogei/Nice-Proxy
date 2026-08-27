package com.niceproxy.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码。
 *
 * 底色固定为白、前景固定为黑，不跟随主题 —— 扫码器对对比度敏感，
 * 用主题色（尤其是暗色模式下的低对比配色）会显著降低识别率。
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    sizePx: Int = 640,
) {
    val bitmap = remember(content, sizePx) { encode(content, sizePx) } ?: return

    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(12.dp),
    )
}

private fun encode(content: String, size: Int): ImageBitmap? = runCatching {
    val hints = mapOf(
        // 分享链接可能很长，纠错等级取 M 在容量与容错之间比较平衡；
        // 取 H 会让长链接的码点密到手机屏幕上扫不出来。
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val bitmap = createBitmap(matrix.width, matrix.height)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bitmap[x, y] = if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    bitmap.asImageBitmap()
}.getOrNull()
