package com.alananasss.kittytune.ui.player.slider

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import com.alananasss.kittytune.data.local.PlayerSliderStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reproduces disabled desktop animations without changing the user's Windows preferences. */
class PlayerSliderMotionScaleTest {
    @Test fun `playhead and seek stay smooth at normal motion scale`() = verify(1f)
    @Test fun `playhead and seek stay smooth when desktop animations are disabled`() = verify(0f)

    private fun verify(scale: Float) {
        var report by mutableLongStateOf(1_000L)
        var running by mutableStateOf(true)
        var direct by mutableStateOf(false)
        var key by mutableStateOf("playing")
        var shown = -1L
        var observedScale: Float? = null
        val motion = object : MotionDurationScale { override val scaleFactor = scale }
        val scene = ImageComposeScene(width = 600, height = 60,
            coroutineContext = Dispatchers.Unconfined + motion) {
            MaterialTheme {
                LaunchedEffect(Unit) { observedScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor }
                val playhead by rememberSmoothPlayhead(report, running, direct, key)
                SideEffect { shown = playhead }
                PlayerSlider(
                    value = playhead.toFloat(),
                    onValueChange = {},
                    onValueChangeFinished = {},
                    sliderStyle = PlayerSliderStyle.BAR,
                    isPlaying = running,
                    valueRange = 0f..60_000f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        fun frame(): Long {
            // SmoothPlayhead uses a monotonic real clock; scene timestamps only request frames.
            Thread.sleep(16)
            scene.render(System.nanoTime()).close()
            return shown
        }
        try {
            val moving = List(14) { frame() }
            assertEquals(scale, observedScale, "The scene must actually exercise the requested motion setting")
            assertTrue(moving.distinct().size >= 4, "Playback must move between decoder reports: $moving")
            assertTrue(moving.last() > moving.first())

            running = false
            key = "paused"
            repeat(8) { frame() }
            assertEquals(1_000L, shown)
            report = 30_000L
            val seek = List(36) { frame() }
            assertTrue(seek.any { it in 1_001L until 30_000L }, "Seek must render intermediate positions: $seek")
            assertEquals(30_000L, seek.last())

            direct = true
            report = 50_000L
            repeat(8) { frame() }
            assertEquals(50_000L, shown, "Dragging must keep exact input rather than adding a trailing animation")
        } finally { scene.close() }
    }
}
