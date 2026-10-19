package com.alananasss.kittytune.data.cover.depth

import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DepthPixelsTest {
    @Test fun `exported model input is RGB zero to one in channel planes without a second normalization`() {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, 0xff8040) }
        val tensor = DepthPixels.input(image)
        val plane = 256 * 256
        assertEquals(plane * 3, tensor.size)
        assertEquals(1f, tensor[0], .00001f)
        assertEquals(128f / 255, tensor[plane], .00001f)
        assertEquals(64f / 255, tensor[plane * 2], .00001f)
        assertTrue(tensor.all { it in 0f..1f })
    }

    @Test fun `inverse depth writes nearer values brighter with a monotonic full eight bit range`() {
        val depth = FloatArray(256 * 256) { 100f + (it % 256) * 2f }
        val map = assertNotNull(DepthPixels.normalizedMap(depth, 256, 256))
        assertEquals(BufferedImage.TYPE_BYTE_GRAY, map.type)
        assertEquals(8, map.colorModel.pixelSize)
        for (x in 0..255) assertEquals(x, map.raster.getSample(x, 100, 0))
        val resized = assertNotNull(DepthPixels.normalizedMap(depth, 512, 320))
        assertEquals(512, resized.width)
        assertEquals(320, resized.height)
        assertEquals(0, resized.raster.getSample(0, 0, 0))
        assertEquals(255, resized.raster.getSample(511, 319, 0))
        assertTrue((1 until 512).all { resized.raster.getSample(it, 160, 0) >= resized.raster.getSample(it - 1, 160, 0) })
    }

    @Test fun `flat nonfinite wrongly shaped and unbounded depth maps fall back instead of fabricating motion`() {
        for (raw in listOf(FloatArray(256 * 256) { 7f }, FloatArray(256 * 256) { Float.NaN },
            FloatArray(256 * 256) { Float.POSITIVE_INFINITY }, floatArrayOf(0f, 1f),
            FloatArray(256 * 256) { if (it == 0) 100f else 100.00001f })) {
            assertNull(DepthPixels.normalizedMap(raw, 256, 256))
        }
        val valid = FloatArray(256 * 256) { it.toFloat() }
        assertNull(DepthPixels.normalizedMap(valid, 513, 256))
        assertNull(DepthPixels.normalizedMap(valid, 0, 256))
    }

    @Test fun `source decoding subsamples before producing an image larger than 512 pixels`() {
        val bytes = png(BufferedImage(1024, 600, BufferedImage.TYPE_INT_RGB))
        val image = assertNotNull(DepthPixels.decode(bytes))
        assertEquals(512, image.width)
        assertEquals(300, image.height)
        assertNull(DepthPixels.decode(byteArrayOf(1, 2, 3)))
    }

    @Test fun `oversized decoded pixel counts are rejected from metadata before allocating their image`() {
        val bytes = png(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB))
        ByteBuffer.wrap(bytes, 16, 4).putInt(32768)
        ByteBuffer.wrap(bytes, 20, 4).putInt(32768)
        val checksum = CRC32().apply { update(bytes, 12, 17) }.value
        ByteBuffer.wrap(bytes, 29, 4).putInt(checksum.toInt())
        assertNull(DepthPixels.decode(bytes))
    }

    private fun png(image: BufferedImage) = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}
