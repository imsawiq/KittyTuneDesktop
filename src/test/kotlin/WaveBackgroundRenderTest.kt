package com.alananasss.kittytune.ui.main

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import com.alananasss.kittytune.core.LocalWindowSeen
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WaveBackgroundRenderTest {
    @Test
    fun cachedWavesKeepMovingAndFreezeWhileWindowIsHidden() {
        var seen by mutableStateOf(true)
        val scene = ImageComposeScene(width = 400, height = 230, coroutineContext = Dispatchers.Unconfined) {
            CompositionLocalProvider(LocalWindowSeen provides seen) {
                WaveBackground(0.3f, listOf(Color.Red, Color.Green, Color.Blue), Modifier.fillMaxSize())
            }
        }
        var time = 0L
        fun frames(count: Int): Long {
            var fingerprint = 0L
            repeat(count) {
                time += 16_000_000L
                scene.render(time).use { image ->
                    if (it == count - 1) {
                        val pixels = image.toComposeImageBitmap().toPixelMap()
                        for (y in 0 until pixels.height step 3) for (x in 0 until pixels.width step 3) {
                            fingerprint = fingerprint * 31 + pixels[x, y].value.toLong()
                        }
                    }
                }
            }
            return fingerprint
        }
        try {
            val initial = frames(5)
            assertNotEquals(initial, frames(90), "Caching must preserve the visible wave motion")
            seen = false
            val hidden = frames(5)
            assertEquals(hidden, frames(90), "A hidden window must stop the decorative animation")
        } finally {
            scene.close()
        }
    }
}
