package com.alananasss.kittytune.data.cover.depth

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal object DepthPixels {
    const val MODEL_SIDE = 256
    const val MAX_IMAGE_SIDE = 512

    fun decode(bytes: ByteArray): BufferedImage? = MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width !in 1..32768 || height !in 1..32768 || width.toLong() * height > 64_000_000) return null
            val stride = ceil(maxOf(width, height).toDouble() / MAX_IMAGE_SIDE).toInt().coerceAtLeast(1)
            reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(stride, stride, 0, 0) })
        } finally { reader.dispose() }
    }

    /** The official exported MiDaS ONNX accepts RGB [0,1], NCHW; normalization is already in the graph. */
    fun input(image: BufferedImage): FloatArray {
        val resized = BufferedImage(MODEL_SIDE, MODEL_SIDE, BufferedImage.TYPE_INT_RGB)
        val graphics = resized.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.drawImage(image, 0, 0, MODEL_SIDE, MODEL_SIDE, null)
        } finally { graphics.dispose() }
        val rgb = resized.getRGB(0, 0, MODEL_SIDE, MODEL_SIDE, null, 0, MODEL_SIDE)
        return FloatArray(rgb.size * 3).also { result ->
            for (channel in 0..2) for (index in rgb.indices) {
                result[channel * rgb.size + index] = (rgb[index] ushr ((2 - channel) * 8) and 255) / 255f
            }
        }
    }

    fun normalizedMap(raw: FloatArray, width: Int, height: Int): BufferedImage? {
        if (width !in 1..MAX_IMAGE_SIDE || height !in 1..MAX_IMAGE_SIDE ||
            raw.size != MODEL_SIDE * MODEL_SIDE || raw.any { !it.isFinite() }) return null
        val minimum = raw.minOrNull() ?: return null
        val maximum = raw.maxOrNull() ?: return null
        val range = maximum - minimum
        if (!range.isFinite() || range <= maxOf(1e-6f, maxOf(abs(minimum), abs(maximum)) * 1e-5f)) return null
        val normalized = FloatArray(raw.size) { (raw[it] - minimum) / range }
        // Bilinear resampling in scalar depth space avoids RGB/gray gamma conversions.
        return BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY).also { map ->
            for (y in 0 until height) for (x in 0 until width) {
                val px = ((x + .5) * MODEL_SIDE / width - .5).coerceIn(0.0, (MODEL_SIDE - 1).toDouble())
                val py = ((y + .5) * MODEL_SIDE / height - .5).coerceIn(0.0, (MODEL_SIDE - 1).toDouble())
                val left = floor(px).toInt(); val top = floor(py).toInt()
                val right = minOf(left + 1, MODEL_SIDE - 1); val bottom = minOf(top + 1, MODEL_SIDE - 1)
                val tx = (px - left).toFloat(); val ty = (py - top).toFloat()
                val a = normalized[top * MODEL_SIDE + left] * (1 - tx) + normalized[top * MODEL_SIDE + right] * tx
                val b = normalized[bottom * MODEL_SIDE + left] * (1 - tx) + normalized[bottom * MODEL_SIDE + right] * tx
                map.raster.setSample(x, y, 0, ((a * (1 - ty) + b * ty) * 255).roundToInt().coerceIn(0, 255))
            }
        }
    }
}
