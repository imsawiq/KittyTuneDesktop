package com.alananasss.kittytune.ui.player.lyrics.editor

import com.alananasss.kittytune.ui.player.lyrics.LyricsUtils
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LyricsTimingDraftTest {
    @Test fun `line timings export valid LRC without dropping punctuation`() {
        var draft = LyricsTimingDraft.fromPlain("Hello, world!\nNext line.")
        val cells = draft.cells(LyricsTimingDraft.Mode.LINE)
        draft = draft.withTime(cells[0], "00:01.125").withTime(cells[1], "00:03.500")
        val exported = draft.exportLrc(LyricsTimingDraft.Mode.LINE, 10_000)
        assertEquals("[00:01.125]Hello, world!\n[00:03.500]Next line.", exported)
        assertEquals(listOf(1_125L, 3_500L), LyricsUtils.parseLrc(exported, 10_000).map { it.startTime })
    }

    @Test fun `word timing preserves spaces and round trips through the player parser`() {
        var draft = LyricsTimingDraft.fromPlain("Hello,  world!\nNext line.")
        val cells = draft.cells(LyricsTimingDraft.Mode.WORD)
        cells.forEachIndexed { index, cell -> draft = draft.withTime(cell, LyricsTimingDraft.formatTime(1_000L + index * 500)) }
        val exported = draft.exportLrc(LyricsTimingDraft.Mode.WORD, 10_000)
        val parsed = LyricsUtils.parseLrc(exported, 10_000)
        assertEquals("Hello,  world!", parsed[0].text)
        assertEquals(listOf("Hello,  ", "world!"), parsed[0].words.map { it.text })
        assertEquals(listOf(1_000L, 1_500L), parsed[0].words.map { it.startTime })
    }

    @Test fun `incomplete backwards duplicate and out of range times cannot be saved`() {
        val original = LyricsTimingDraft.fromPlain("First\nSecond")
        val cells = original.cells(LyricsTimingDraft.Mode.LINE)
        assertFalse(original.isComplete(LyricsTimingDraft.Mode.LINE, 10_000))
        for (time in listOf("invalid", "00:60.000", "00:00.999", "00:01.000", "00:10.000")) {
            val draft = original.withTime(cells[0], "00:01.000").withTime(cells[1], time)
            assertFalse(draft.isComplete(LyricsTimingDraft.Mode.LINE, 10_000), time)
            assertFailsWith<IllegalArgumentException> { draft.exportLrc(LyricsTimingDraft.Mode.LINE, 10_000) }
        }
    }

    @Test fun `switching granularity preserves each modes draft`() {
        val original = LyricsTimingDraft.fromPlain("One two")
        val line = original.cells(LyricsTimingDraft.Mode.LINE).single()
        val word = original.cells(LyricsTimingDraft.Mode.WORD).first()
        val draft = original.withTime(line, "00:02.000").withTime(word, "00:03.000")
        assertEquals("00:02.000", draft.cells(LyricsTimingDraft.Mode.LINE).single().time)
        assertEquals("00:03.000", draft.cells(LyricsTimingDraft.Mode.WORD).first().time)
    }

    @Test fun `time parsing is locale independent and rejects negative or overflowing seconds`() {
        assertEquals(61_500L, LyricsTimingDraft.parseTime("01:01,5"))
        assertEquals(1_050L, LyricsTimingDraft.parseTime("00:01.05"))
        assertNull(LyricsTimingDraft.parseTime("-1:00.000"))
        assertNull(LyricsTimingDraft.parseTime("00:61"))
        assertEquals("01:01.500", LyricsTimingDraft.formatTime(61_500))
    }

    @Test fun `oversized text is rejected instead of silently dropping verses`() {
        val oversized = (1..2_001).joinToString("\n") { "line" }
        assertFalse(LyricsTimingDraft.canPrepare(oversized))
        assertFailsWith<IllegalArgumentException> { LyricsTimingDraft.fromPlain(oversized) }
    }
}
