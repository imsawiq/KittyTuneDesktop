package com.alananasss.kittytune.data.artist.portrait

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.roundToInt

internal object PortraitPixels {
    const val MODEL_SIDE = 320
    private const val MAX_SIDE = 768

    fun decode(bytes: ByteArray): BufferedImage? {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input == null) return null
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width !in 1..32768 || height !in 1..32768 || width.toLong() * height > 64_000_000) return null
                val stride = ceil(maxOf(width, height).toDouble() / MAX_SIDE).toInt().coerceAtLeast(1)
                val options = reader.defaultReadParam.apply { setSourceSubsampling(stride, stride, 0, 0) }
                return reader.read(0, options)
            } finally {
                reader.dispose()
            }
        }
    }

    /** Matches rembg U2netp: RGB / image maximum, ImageNet mean/std, NCHW. */
    fun input(image: BufferedImage): FloatArray {
        val resized = resize(image, MODEL_SIDE, MODEL_SIDE)
        val pixels = resized.getRGB(0, 0, MODEL_SIDE, MODEL_SIDE, null, 0, MODEL_SIDE)
        val maximum = pixels.maxOf { maxOf(it ushr 16 and 255, it ushr 8 and 255, it and 255) }.coerceAtLeast(1).toFloat()
        val means = floatArrayOf(.485f, .456f, .406f)
        val deviations = floatArrayOf(.229f, .224f, .225f)
        return FloatArray(pixels.size * 3).also { output ->
            for (channel in 0..2) for (index in pixels.indices) {
                val value = pixels[index] ushr ((2 - channel) * 8) and 255
                output[channel * pixels.size + index] = (value / maximum - means[channel]) / deviations[channel]
            }
        }
    }

    fun cutout(image: BufferedImage, rawMask: FloatArray): BufferedImage? {
        if (rawMask.size != MODEL_SIDE * MODEL_SIDE || rawMask.any { !it.isFinite() }) return null
        val minimum = rawMask.minOrNull() ?: return null
        val maximum = rawMask.maxOrNull() ?: return null
        if (maximum - minimum < .01f) return null
        val mask = BufferedImage(MODEL_SIDE, MODEL_SIDE, BufferedImage.TYPE_INT_RGB)
        var foreground = 0
        var background = 0
        var left = MODEL_SIDE
        var right = -1
        var top = MODEL_SIDE
        var bottom = -1
        for (index in rawMask.indices) {
            val alpha = ((rawMask[index] - minimum) / (maximum - minimum) * 255).roundToInt().coerceIn(0, 255)
            if (alpha > 192) {
                foreground++
                left = minOf(left, index % MODEL_SIDE)
                right = maxOf(right, index % MODEL_SIDE)
                top = minOf(top, index / MODEL_SIDE)
                bottom = maxOf(bottom, index / MODEL_SIDE)
            }
            if (alpha < 64) background++
            mask.setRGB(index % MODEL_SIDE, index / MODEL_SIDE, alpha * 0x010101)
        }
        // A portrait must contain both subject and background; reject unusable network outputs.
        if (foreground < rawMask.size / 100 || background < rawMask.size / 100) return null
        // Abstract avatars can yield only a narrow stripe. Keep the original rather than show that as a person.
        if (right - left + 1 < MODEL_SIDE / 4 || bottom - top + 1 < MODEL_SIDE / 4) return null
        val scaledMask = resize(mask, image.width, image.height)
        return BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).also { output ->
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val original = image.getRGB(x, y)
                val alpha = (original ushr 24) * (scaledMask.getRGB(x, y) and 255) / 255
                output.setRGB(x, y, (alpha shl 24) or (original and 0x00ffffff))
            }
        }
    }

    private fun resize(image: BufferedImage, width: Int, height: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { resized ->
            resized.createGraphics().let { graphics ->
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    graphics.drawImage(image, 0, 0, width, height, null)
                } finally {
                    graphics.dispose()
                }
            }
        }
}
