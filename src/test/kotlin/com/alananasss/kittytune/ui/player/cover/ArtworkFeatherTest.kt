package com.alananasss.kittytune.ui.player.cover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.test.assertTrue

class ArtworkFeatherTest {
    @Test fun `soft edges reveal the background while preserving the sharp centre`() {
        val pixels = render(1f)
        assertTrue(pixels[0, 100].blue > 0.99f)
        assertTrue(pixels[0, 0].blue > 0.99f)
        assertTrue(pixels[100, 100].red > 0.99f)
        val transition = listOf(5, 20, 40, 60, 80).map { pixels[it, 100].red }
        assertTrue(transition.zipWithNext().all { (a, b) -> b > a }, "Edge must fade continuously into the centre: $transition")
    }

    @Test fun `zero strength leaves every corner intact`() {
        val pixels = render(0f)
        for ((x, y) in listOf(0 to 0, 199 to 0, 0 to 199, 199 to 199, 100 to 100)) {
            assertTrue(pixels[x, y].red > 0.99f && pixels[x, y].blue < 0.01f)
        }
    }

    private fun render(amount: Float): PixelMap {
        val scene = ImageComposeScene(width = 200, height = 200, coroutineContext = Dispatchers.Unconfined) {
            Box(Modifier.size(200.dp).background(Color.Blue)) {
                Box(Modifier.size(200.dp).featheredArtwork(amount).background(Color.Red))
            }
        }
        try {
            return scene.render(0L).use { it.toComposeImageBitmap().toPixelMap() }
        } finally { scene.close() }
    }
}
