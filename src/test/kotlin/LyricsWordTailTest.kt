package com.alananasss.kittytune.ui.player.lyrics

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsWordTailTest {
    private fun heldLine(end: Long = 4_000L, background: Boolean = false) = LyricLine(
        "hills", 1_000, 2_000,
        words = listOf(LyricWord("hills", 1_800, end, isBackground = background)),
    )

    @Test
    fun `shared fill keeps growing through the last word after the declared line end`() {
        val position = mutableFloatStateOf(2_000f)
        val scene = ImageComposeScene(width = 300, height = 90, coroutineContext = Dispatchers.Unconfined) {
            LyricLineText(
                line = heldLine(), isActive = true, positionMs = { position.floatValue },
                wordSync = true, fillEffect = true,
                activeStyle = TextStyle(fontSize = 48.sp), inactiveStyle = TextStyle(fontSize = 48.sp),
                activeColor = Color.White, inactiveColor = Color.Black, unsungColor = Color.Black,
                textAlign = TextAlign.Left,
            )
        }
        fun brightness(): Double = scene.render().use { image ->
            val pixels = image.toComposeImageBitmap().toPixelMap()
            var total = 0.0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) total += pixels[x, y].red
            total
        }
        try {
            brightness() // Initial text layout becomes available to the draw layer.
            val early = brightness()
            position.floatValue = 3_900f
            val late = brightness()
            assertTrue(late > early * 2, "A held final word must continue filling after the line's rounded end")
            position.floatValue = 2_000f
            val rewound = brightness()
            assertEquals(early, rewound, 1.0, "A backward seek must clear the reused fill path")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a word tail prevents a false instrumental break`() {
        val lines = listOf(heldLine(), LyricLine("next", 7_000, 8_000))
        assertEquals(lines, LyricsUtils.insertInstrumentalBreaks(lines))
    }

    @Test
    fun `a true pause begins after the last explicitly timed backing word`() {
        val lines = LyricsUtils.insertInstrumentalBreaks(listOf(heldLine(background = true), LyricLine("next", 9_000, 10_000)))
        val pause = lines.single { it.isInstrumental }
        assertEquals(4_000L, pause.startTime)
        assertEquals(9_000L, pause.endTime)
        assertEquals(5_000L, pause.durationMs)
    }

    @Test
    fun `the next word onset bounds a genuine pause before its rounded line start`() {
        val next = LyricLine("next", 9_000, 10_000, listOf(LyricWord("next", 7_000, 10_000)))
        val result = LyricsUtils.insertInstrumentalBreaks(listOf(heldLine(), next))
        assertFalse(result.any { it.isInstrumental }, "Only three seconds remain before the next sung word")
    }

    @Test
    fun `overlapping tails do not delay the next line or remove repeated phrases`() {
        val first = heldLine(end = 7_000)
        val second = first.copy(startTime = 5_000, endTime = 8_000, words = listOf(LyricWord("hills", 5_000, 8_000)))
        val result = LyricsUtils.insertInstrumentalBreaks(listOf(first, second))
        assertEquals(listOf(first, second), result)
        assertEquals(7_000L, first.effectiveEndTime)
        assertEquals(1, LyricsUtils.activeLineIndex(result, 5_100))
    }

    @Test
    fun `declared pauses remain unchanged for line timed lyrics`() {
        val first = LyricLine("hello", 1_000, 2_000)
        val result = LyricsUtils.insertInstrumentalBreaks(listOf(first, LyricLine("next", 8_000, 9_000)))
        assertEquals(2_000L, first.effectiveEndTime)
        assertEquals(2_000L, result[1].startTime)
        assertEquals(8_000L, result[1].endTime)
        assertTrue(result[1].isInstrumental)
    }

    @Test
    fun `intro ends at the first actual word onset and short intros are not invented`() {
        val early = LyricLine("hello", 8_000, 10_000, listOf(LyricWord("hello", 4_000, 10_000)))
        assertEquals(listOf(early), LyricsUtils.insertInstrumentalBreaks(listOf(early)))
        val later = early.copy(words = listOf(LyricWord("hello", 6_000, 10_000)))
        val result = LyricsUtils.insertInstrumentalBreaks(listOf(later))
        assertEquals(6_000L, result.first().endTime)
        assertEquals(6_000L, result.first().durationMs)
        assertEquals(1, LyricsUtils.activeLineIndex(result, 6_000))
    }
}
