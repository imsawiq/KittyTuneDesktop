package com.alananasss.kittytune.ui.player.cover

import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DepthArtworkRendererTest {
    @Test fun `foreground and background move in opposite directions rather than panning the whole image`() {
        renderer().use { renderer ->
            val right = render(renderer, 4f)
            val left = render(renderer, 12f)
            assertTrue(right[256, 128].red - left[256, 128].red > 0.005f)
            assertTrue(left[256, 384].red - right[256, 384].red > 0.005f)
        }
    }

    @Test fun `full cycle repeats without exposing transparent image edges`() {
        renderer().use { renderer ->
            val start = render(renderer, 4f)
            val repeated = render(renderer, 20f)
            for ((x, y) in listOf(0 to 0, 511 to 0, 0 to 511, 511 to 511, 256 to 128)) {
                assertEquals(1f, start[x, y].alpha, 0.001f)
                assertEquals(start[x, y].red, repeated[x, y].red, 0.001f)
            }
        }
        val before = depthCoverMotion(15.999f)
        val after = depthCoverMotion(0.001f)
        assertEquals(before.first, after.first, 0.00002f)
        assertEquals(before.second, after.second, 0.00002f)
    }

    @Test fun `fit preserves the image aspect ratio and transparent letterboxing`() {
        renderer().use { renderer ->
            Surface.makeRasterN32Premul(800, 400).use { surface ->
                surface.canvas.clear(0)
                renderer.draw(surface.canvas, 800f, 400f, 4f, ContentScale.Fit)
                val pixels = surface.makeImageSnapshot().use { it.toComposeImageBitmap().toPixelMap() }
                assertEquals(0f, pixels[40, 200].alpha, 0.001f)
                assertEquals(1f, pixels[400, 200].alpha, 0.001f)
                assertEquals(0f, pixels[760, 200].alpha, 0.001f)
            }
        }
    }

    private fun render(renderer: DepthArtworkRenderer, seconds: Float): PixelMap =
        Surface.makeRasterN32Premul(512, 512).use { surface ->
            surface.canvas.clear(0)
            renderer.draw(surface.canvas, 512f, 512f, seconds, ContentScale.Crop)
            surface.makeImageSnapshot().use { it.toComposeImageBitmap().toPixelMap() }
        }

    private fun renderer(): DepthArtworkRenderer {
        val artwork = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
        val depth = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 512) for (x in 0 until 512) {
            val level = x * 255 / 511
            artwork.setRGB(x, y, (0xff shl 24) or (level shl 16))
            depth.setRGB(x, y, if (y < 256) -1 else 0xff000000.toInt())
        }
        return DepthArtworkRenderer.create(encoded(artwork), encoded(depth))
    }

    private fun encoded(image: BufferedImage): Image = ByteArrayOutputStream().use { output ->
        check(ImageIO.write(image, "png", output))
        Image.makeFromEncoded(output.toByteArray())
    }
}
