package com.niceproxy.feature.scan

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 相机帧的二维码识别。
 *
 * 只取 Y 平面（亮度）而不做 YUV→RGB 转换：二维码是纯黑白图形，
 * 色度信息对识别毫无帮助，转换却要在每一帧上做一次全图运算。
 */
class QrAnalyzer(
    private val onDecoded: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
    }

    /** 识别成功后立刻停止：后续帧再解出同一个码只会导致重复导入。 */
    private val consumed = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        if (consumed.get()) {
            image.close()
            return
        }
        image.use { proxy ->
            val plane = proxy.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining()).also(buffer::get)

            val source = PlanarYUVLuminanceSource(
                data,
                plane.rowStride,
                proxy.height,
                0,
                0,
                proxy.width.coerceAtMost(plane.rowStride),
                proxy.height,
                false,
            )

            val text = decode(source) ?: decode(source.invert())
            if (text != null && consumed.compareAndSet(false, true)) {
                onDecoded(text)
            }
        }
    }

    private fun decode(source: com.google.zxing.LuminanceSource): String? = runCatching {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull().also { reader.reset() }

    companion object {
        /** 从相册选中的图片里识别二维码。 */
        fun decodeBitmap(bitmap: Bitmap): String? {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val reader = MultiFormatReader().apply {
                setHints(
                    mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        DecodeHintType.TRY_HARDER to true,
                    ),
                )
            }
            // 截图分享的二维码常有反色或深色主题，两个方向都试一次
            return runCatching { reader.decode(BinaryBitmap(HybridBinarizer(source))).text }
                .recoverCatching {
                    reader.decode(BinaryBitmap(HybridBinarizer(source.invert()))).text
                }
                .getOrNull()
        }
    }
}
