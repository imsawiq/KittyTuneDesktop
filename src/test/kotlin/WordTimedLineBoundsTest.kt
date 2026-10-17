import com.alananasss.kittytune.ui.player.lyrics.LyricLine
import com.alananasss.kittytune.ui.player.lyrics.LyricWord
import com.alananasss.kittytune.ui.player.lyrics.LyricsUtils
import com.alananasss.kittytune.ui.player.lyrics.buildSyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WordTimedLineBoundsTest {
    @Test
    fun wordStartsBeforeDeclaredLineAndIsVisibleFromItsOwnStart() {
        val line = LyricLine(
            text = "first word",
            startTime = 2_000,
            endTime = 4_000,
            words = listOf(LyricWord("first ", 1_000, 2_000), LyricWord("word", 2_000, 4_000)),
        )

        val karaoke = assertIs<KaraokeLine.MainKaraokeLine>(
            buildSyncedLyrics(listOf(line), isWordSynced = true).lines.single(),
        )

        assertEquals(1_000, karaoke.start)
        assertEquals(1_000, karaoke.syllables.first().start)
        assertEquals(-1, LyricsUtils.activeLineIndex(listOf(line), 999))
        assertEquals(0, LyricsUtils.activeLineIndex(listOf(line), 1_000))
    }

    @Test
    fun finalWordFinishesEvenWhenDeclaredLineEndsEarlier() {
        val line = LyricLine(
            text = "still singing",
            startTime = 1_000,
            endTime = 2_000,
            words = listOf(LyricWord("still ", 1_000, 1_500), LyricWord("singing", 1_500, 3_000)),
        )

        val karaoke = assertIs<KaraokeLine.MainKaraokeLine>(
            buildSyncedLyrics(listOf(line), isWordSynced = true).lines.single(),
        )

        assertEquals(3_000, karaoke.end)
        assertEquals(3_000, karaoke.syllables.last().end)
    }
}
