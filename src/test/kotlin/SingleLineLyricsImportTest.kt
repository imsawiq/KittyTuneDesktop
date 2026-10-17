package com.alananasss.kittytune.ui.player.lyrics

import com.alananasss.kittytune.ui.player.ManualLyricsSearch
import com.alananasss.kittytune.ui.player.LyricsSearchTrack
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleLineLyricsImportTest {
    @Test fun `one explicitly timed line starting at zero survives LOCAL import`() {
        val raw = "[00:00.000]One complete verse."
        val parsed = LyricsUtils.parseLyricsContent(raw, 30_000)
        assertEquals(1, parsed.size)
        assertEquals(0L, parsed.single().startTime)
        assertEquals(30_000L, parsed.single().endTime)
        val result = ManualLyricsSearch.localResult(LyricsSearchTrack("1", "Song", "Artist", null, 30_000), raw)
        assertEquals(true, result?.hasLineSync)
        assertEquals(raw, result?.rawContent)
    }

    @Test fun `word timing on one line at zero retains every word and exact timings`() {
        val parsed = LyricsUtils.parseLyricsContent("[00:00.000]<00:00.000>Hello, <00:02.500>world!", 30_000)
        assertEquals("Hello, world!", parsed.single().text)
        assertEquals(listOf(0L, 2_500L), parsed.single().words.map { it.startTime })
        assertEquals(listOf(2_500L, 30_000L), parsed.single().words.map { it.endTime })
    }

    @Test fun `multiple fake zero lines are still rejected`() {
        val raw = "[00:00.000]One\n[00:00.000]Two\n[00:00.000]Three"
        assertTrue(LyricsUtils.parseLyricsContent(raw, 30_000).isEmpty())
    }

    @Test fun `plain single line does not acquire manufactured timestamps`() {
        assertTrue(LyricsUtils.parseLyricsContent("Just a verse, with no timestamps.", 30_000).isEmpty())
        assertFalse(LyricsUtils.hasRealTimings(listOf(LyricLine("Untimed", 0, 30_000))))
    }
}
