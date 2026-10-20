package com.alananasss.kittytune.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.bytedeco.javacv.Frame
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer

/** Copies a BGRA decoder frame once into the bitmap retained by Compose. */
internal fun clipFrameImage(decoded: Frame): ImageBitmap? {
    val buffer = decoded.image?.firstOrNull() as? ByteBuffer ?: return null
    val width = decoded.imageWidth
    val height = decoded.imageHeight
    val stride = decoded.imageStride
    if (width <= 0 || height <= 0 || stride.toLong() < width.toLong() * 4L) return null
    val byteCount = stride.toLong() * height
    val requiredBytes = stride.toLong() * (height - 1) + width.toLong() * 4L
    if (byteCount > Int.MAX_VALUE || requiredBytes > buffer.remaining()) return null
    val pixels = ByteArray(byteCount.toInt())
    buffer.duplicate().get(pixels, 0, minOf(pixels.size, buffer.remaining()))
    val bitmap = Bitmap()
    try {
        if (!bitmap.installPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), pixels, stride)) {
            bitmap.close()
            return null
        }
        bitmap.setImmutable()
        return bitmap.asComposeImageBitmap()
    } catch (error: Throwable) {
        bitmap.close()
        throw error
    }
}
