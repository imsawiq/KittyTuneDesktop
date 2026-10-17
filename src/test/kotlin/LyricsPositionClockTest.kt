package com.alananasss.kittytune.ui.player.lyrics.timing

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.ImageComposeScene
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsPositionClockTest {
    @Test fun `fresh reports keep word reveal moving beyond the first 400 milliseconds`() {
        val clock = LyricsPositionClock()
        var shown = 0f
        repeat(301) { frame ->
            val nowMs = frame * 16L
            val report = nowMs / 200L * 200L
            shown = clock.step(report, 1f, nowMs * 1_000_000L)
            assertTrue(shown in report.toFloat()..(report + 400L).toFloat())
        }
        assertTrue(shown >= 4_800f, "The first anchor must not freeze the entire song at $shown ms")
    }

    @Test fun `a stalled decoder only allows a bounded estimate`() {
        val clock = LyricsPositionClock()
        clock.step(10_000L, 1f, 0L)
        assertEquals(10_400f, clock.step(10_000L, 1f, 20_000_000_000L))
    }

    @Test fun `seeks reset the word clock immediately in both directions`() {
        val clock = LyricsPositionClock()
        clock.step(5_000L, 1f, 0L)
        clock.step(5_000L, 1f, 100_000_000L)
        assertEquals(2_000f, clock.step(2_000L, 1f, 116_000_000L))
        assertEquals(60_000f, clock.step(60_000L, 1f, 132_000_000L))
    }

    @Test fun `paused seeking observes the newest position callback without restarting playback`() {
        val reported = mutableLongStateOf(1_000L)
        var shown = -1f
        val scene = ImageComposeScene(width = 40, height = 40) {
            val position = reported.longValue
            val smooth = rememberSmoothPosition({ position }, isPlaying = false, speed = 1f)
            val current = smooth.value
            SideEffect { shown = current }
        }
        var frame = 0L
        fun render() { frame += 16_000_000L; scene.render(frame).close() }
        try {
            repeat(3) { render() }
            assertEquals(1_000f, shown)
            reported.longValue = 8_000L
            repeat(4) { render() }
            assertEquals(8_000f, shown)
            reported.longValue = 2_000L
            repeat(4) { render() }
            assertEquals(2_000f, shown)
        } finally { scene.close() }
    }
}
