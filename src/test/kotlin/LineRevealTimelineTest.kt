package com.alananasss.kittytune.ui.player.lyrics

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LineRevealTimelineTest {
    @Test
    fun `plain imported text is cleaned and paced including instrumental breaks`() {
        val lines = pacedPlainLyrics("[00:00.00]Hello there\n\n[00:03.00]Goodbye", 1f)
        assertEquals(listOf("Hello there", "", "Goodbye"), lines.map { it.text })
        assertTrue(lines[1].isInstrumental)
        assertEquals(lines[0].endTime, lines[1].startTime)
        assertEquals(lines[1].endTime, lines[2].startTime)
        assertTrue(lines[0].endTime > 0)
    }

    @Test
    fun `single word reveals over time instead of snapping to its final color`() {
        val line = LyricLine("Echo", 1_000, 5_000).withRevealedWords()
        val word = line.words.single()
        assertEquals(1_000L, word.startTime)
        assertTrue(word.endTime - word.startTime >= 100, "Single words need a visible fill interval")
        assertTrue(word.endTime < line.endTime)
    }

    @Test
    fun `word timestamps from a provider are never regenerated`() {
        val original = LyricLine("Hello", 1_000, 5_000, words = listOf(LyricWord("Hello", 1_200, 2_000)))
        assertSame(original, original.withRevealedWords())
    }

    @Test
    fun `single backing word uses the same visible reveal on the full lyrics page`() {
        val source = LyricLine("Stay (echo)", 1_000, 5_000)
        val lyrics = buildSyncedLyrics(listOf(source), isWordSynced = false, splitBacking = true, revealWords = true)
        val main = lyrics.lines.single() as KaraokeLine.MainKaraokeLine
        val backing = main.accompanimentLines!!.single().syllables.single()
        assertEquals("echo", backing.content)
        assertTrue(backing.end - backing.start >= 100)
        assertTrue(backing.start > main.start)
    }

    @Test
    fun `missing line end uses next line but reveal stays inside its interval`() {
        val result = LyricLine("one two three", 1_000, 0).withRevealedWords(1_100)
        assertEquals(1_100L, result.endTime)
        assertTrue(result.words.all { it.startTime >= 1_000 && it.endTime <= 1_100 && it.endTime > it.startTime })
        assertEquals("one two three", result.words.joinToString("") { it.text })
    }

    @Test
    fun `one line renderer wraps provider words and preserves spaces`() {
        var measured: TextLayoutResult? = null
        val line = LyricLine(
            text = "Hello wonderful world again",
            startTime = 0,
            endTime = 5_000,
            words = listOf("Hello", "wonderful", "world", "again").mapIndexed { index, text ->
                LyricWord(text, index * 1_000L, (index + 1) * 1_000L)
            },
        )
        val scene = ImageComposeScene(width = 140, height = 300) {
            MaterialTheme {
                LyricLineText(
                    line = line,
                    isActive = true,
                    positionMs = { 1_500f },
                    wordSync = true,
                    fillEffect = true,
                    activeStyle = TextStyle(fontSize = 22.sp),
                    inactiveStyle = TextStyle(fontSize = 22.sp),
                    activeColor = Color.White,
                    inactiveColor = Color.Gray,
                    unsungColor = Color.DarkGray,
                    textAlign = TextAlign.Center,
                    fillWidth = false,
                    onTextLayout = { measured = it },
                )
            }
        }
        try {
            scene.render()
            val result = assertNotNull(measured)
            assertEquals(line.text, result.layoutInput.text.text)
            assertTrue(result.lineCount > 1)
            assertTrue(!result.hasVisualOverflow, "Text must wrap within the card")
        } finally {
            scene.close()
        }
    }
}
