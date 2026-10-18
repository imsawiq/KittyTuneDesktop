package com.alananasss.kittytune.data.artist.portrait

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortraitPixelsTest {
    @Test fun `model receives normalized RGB channel planes instead of interleaved or BGR pixels`() {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, 0xff8040) }
        val tensor = PortraitPixels.input(image)
        val plane = 320 * 320
        assertEquals(plane * 3, tensor.size)
        assertEquals((1f - .485f) / .229f, tensor[0], .0001f)
        assertEquals((128f / 255 - .456f) / .224f, tensor[plane], .0001f)
        assertEquals((64f / 255 - .406f) / .225f, tensor[plane * 2], .0001f)
        assertTrue(PortraitPixels.input(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)).all { it.isFinite() })
    }

    @Test fun `cutout preserves original colors and transparency and removes only the background`() {
        val image = BufferedImage(320, 320, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 320) for (x in 0 until 320) image.setRGB(x, y, 0x804080c0.toInt())
        val mask = FloatArray(320 * 320) { index -> if (index % 320 in 80..239) 1f else 0f }
        val result = assertNotNull(PortraitPixels.cutout(image, mask))
        assertEquals(0x804080c0.toInt(), result.getRGB(160, 160))
        assertEquals(0x004080c0, result.getRGB(0, 160))
    }

    @Test fun `nonfinite wrongly shaped nearly uniform or almost full masks fall back to avatar`() {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        for (mask in listOf(
            floatArrayOf(0f, 1f),
            FloatArray(320 * 320) { Float.NaN },
            FloatArray(320 * 320) { 1f },
            FloatArray(320 * 320) { if (it == 0) .995f else 1f },
            FloatArray(320 * 320) { if (it == 0) 0f else 1f },
        )) assertNull(PortraitPixels.cutout(image, mask))
    }

    @Test fun `image decoding rejects malformed bytes and subsamples before allocating full image`() {
        assertNull(PortraitPixels.decode(byteArrayOf(1, 2, 3)))
        val image = BufferedImage(1600, 1000, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val decoded = assertNotNull(PortraitPixels.decode(bytes))
        assertTrue(decoded.width <= 768 && decoded.height <= 768)
        assertEquals(534, decoded.width)
        assertEquals(334, decoded.height)
    }

    @Test fun `a narrow logo stripe is not presented as an artist silhouette`() {
        val image = BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB)
        val vertical = FloatArray(320 * 320) { if (it % 320 in 135..184) 1f else 0f }
        val horizontal = FloatArray(320 * 320) { if (it / 320 in 135..184) 1f else 0f }
        assertNull(PortraitPixels.cutout(image, vertical))
        assertNull(PortraitPixels.cutout(image, horizontal))
    }
}
