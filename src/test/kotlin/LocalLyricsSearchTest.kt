import com.alananasss.kittytune.data.LyricsMatcher
import com.alananasss.kittytune.ui.player.LyricsSearchTrack
import com.alananasss.kittytune.ui.player.ManualLyricsSearch
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalLyricsSearchTest {
    private val track = LyricsSearchTrack("-123", "Song", "Artist", "Album", 180_000L)
    private val target = LyricsMatcher.Target("Song", "Artist", 180_000L)

    @Test
    fun `local plain lyrics retain source content and track metadata`() {
        val result = assertNotNull(ManualLyricsSearch.localResult(track, "First line\nSecond line"))

        assertEquals("LOCAL", result.provider)
        assertEquals("-123", result.id)
        assertEquals(track.title, result.name)
        assertEquals(track.artist, result.artistName)
        assertEquals(track.album, result.albumName)
        assertEquals(180.0, result.durationSec)
        assertEquals("First line\nSecond line", result.rawContent)
        assertFalse(result.hasLineSync)
        assertFalse(result.hasWordSync)
        assertEquals("First line • Second line", result.previewText)
    }

    @Test
    fun `local timing metadata comes from parsed lyrics`() {
        val lineResult = assertNotNull(ManualLyricsSearch.localResult(track, "[00:01.00]First\n[00:02.00]Second"))
        val wordResult = assertNotNull(ManualLyricsSearch.localResult(track, "[00:01.00]<00:01.00>First <00:01.50>word\n[00:02.00]Second"))

        assertTrue(lineResult.hasLineSync)
        assertFalse(lineResult.hasWordSync)
        assertTrue(wordResult.hasLineSync)
        assertTrue(wordResult.hasWordSync)
    }

    @Test
    fun `local lyrics stay first when online timed results arrive`() {
        val local = assertNotNull(ManualLyricsSearch.localResult(track, "Imported lyrics"))
        val online = local.copy(id = "online", provider = "LRCLIB", hasLineSync = true, hasWordSync = true)

        val results = ManualLyricsSearch.merge(listOf(local), listOf(online), target)

        assertEquals(listOf(local, online), results)
        assertEquals(results, ManualLyricsSearch.merge(results, listOf(online), target))
    }

    @Test
    fun `blank imports do not produce an override`() {
        assertNull(ManualLyricsSearch.localResult(track, " \n\t"))
    }
}
