package com.alananasss.kittytune.media

import androidx.compose.ui.graphics.asSkiaBitmap
import org.bytedeco.javacv.Frame
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClipFrameImageTest {
    @Test fun `padded BGRA rows keep colors and survive reuse of the decoder buffer`() {
        val pixels = ByteBuffer.allocateDirect(28)
        pixels.position(4)
        pixels.put(byteArrayOf(0, 0, -1, -1, 0, -1, 0, -1, 0, 0, 0, 0))
        pixels.put(byteArrayOf(-1, 0, 0, -1, -1, -1, -1, -1, 0, 0, 0, 0))
        pixels.position(4)
        val frame = decodedFrame(2, 2, 12, pixels)
        val image = assertNotNull(clipFrameImage(frame))
        val bitmap = image.asSkiaBitmap()
        try {
            assertEquals(4, pixels.position())
            assertEquals(2, image.width)
            assertEquals(2, image.height)
            assertEquals(0xffff0000.toInt(), bitmap.getColor(0, 0))
            assertEquals(0xff00ff00.toInt(), bitmap.getColor(1, 0))
            assertEquals(0xff0000ff.toInt(), bitmap.getColor(0, 1))
            assertEquals(0xffffffff.toInt(), bitmap.getColor(1, 1))
            for (index in 4 until pixels.limit()) pixels.put(index, 0)
            assertEquals(0xffff0000.toInt(), bitmap.getColor(0, 0))
            assertEquals(0xffffffff.toInt(), bitmap.getColor(1, 1))
        } finally { bitmap.close() }
    }

    @Test fun `invalid stride truncated data and overflowing dimensions are rejected`() {
        assertNull(clipFrameImage(decodedFrame(2, 2, 7, ByteBuffer.allocate(14))))
        assertNull(clipFrameImage(decodedFrame(2, 2, 8, ByteBuffer.allocate(15))))
        assertNull(clipFrameImage(decodedFrame(Int.MAX_VALUE, 2, Int.MAX_VALUE, ByteBuffer.allocate(1))))
        assertNull(clipFrameImage(decodedFrame(1, Int.MAX_VALUE, 4, ByteBuffer.allocate(1))))
        assertNull(clipFrameImage(Frame()))
    }

    @Test fun `last row does not require unused trailing padding bytes`() {
        val pixels = ByteBuffer.allocate(20)
        pixels.put(12, -1)
        pixels.put(15, -1)
        val bitmap = assertNotNull(clipFrameImage(decodedFrame(2, 2, 12, pixels))).asSkiaBitmap()
        try { assertEquals(0xff0000ff.toInt(), bitmap.getColor(0, 1)) }
        finally { bitmap.close() }
    }

    private fun decodedFrame(width: Int, height: Int, stride: Int, pixels: ByteBuffer) = Frame().apply {
        imageWidth = width
        imageHeight = height
        imageStride = stride
        image = arrayOf(pixels)
    }
}
