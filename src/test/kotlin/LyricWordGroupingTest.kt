import com.alananasss.kittytune.ui.player.lyrics.accompanist.groupIntoWords
import com.alananasss.kittytune.ui.player.lyrics.accompanist.calculateGreedyWrappedLines
import com.alananasss.kittytune.ui.player.lyrics.accompanist.measureSyllablesAndDetermineAnimation
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import kotlin.test.assertEquals
import org.junit.Test

class LyricWordGroupingTest {
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    @Test
    fun `narrow lyric layout does not wrap a separately timed comma onto next row`() {
        androidx.compose.ui.platform.registerSkikoComposeImplementation()
        val measurer = androidx.compose.ui.text.TextMeasurer(
            androidx.compose.ui.text.font.createFontFamilyResolver(),
            androidx.compose.ui.unit.Density(1f),
            androidx.compose.ui.unit.LayoutDirection.Ltr,
        )
        val style = androidx.compose.ui.text.TextStyle.Default
        val syllables = listOf(
            KaraokeSyllable("Hello ", 0, 500),
            KaraokeSyllable(", ", 500, 550),
            KaraokeSyllable("world", 550, 1000),
        )
        val layouts = measureSyllablesAndDetermineAnimation(
            syllables, measurer, style, style, isAccompanimentLine = false,
            spaceWidth = measurer.measure(" ", style).size.width.toFloat(),
        )

        val rows = calculateGreedyWrappedLines(
            layouts, measurer.measure("Hello", style).size.width.toFloat(), measurer, style,
        )

        assertEquals(2, rows.size)
        assertEquals(listOf("Hello ", ","), rows.first().syllables.map { it.syllable.content })
        assertEquals("world", rows.last().syllables.single().syllable.content)
    }

    @Test
    fun `separate comma stays with previous word and retains its timing`() {
        val syllables = listOf(
            KaraokeSyllable("Hello ", 0, 500),
            KaraokeSyllable(", ", 500, 550),
            KaraokeSyllable("world", 550, 1000),
        )

        assertEquals(listOf(syllables.take(2), syllables.drop(2)), groupIntoWords(syllables))
    }

    @Test
    fun `punctuation without trailing space does not absorb next word`() {
        val syllables = listOf(
            KaraokeSyllable("Hello ", 0, 500),
            KaraokeSyllable(",", 500, 550),
            KaraokeSyllable("world", 550, 1000),
        )

        assertEquals(listOf(syllables.take(2), syllables.drop(2)), groupIntoWords(syllables))
    }

    @Test
    fun `closing quote and full stop attach while opening bracket stays with next word`() {
        val syllables = listOf(
            KaraokeSyllable("Hello ", 0, 100),
            KaraokeSyllable("”", 100, 110),
            KaraokeSyllable(". ", 110, 120),
            KaraokeSyllable("(", 120, 130),
            KaraokeSyllable("world)", 130, 300),
        )

        assertEquals(listOf(syllables.take(3), syllables.drop(3)), groupIntoWords(syllables))
    }

    @Test
    fun `ordinary syllables and standalone dash keep their word boundaries`() {
        val syllables = listOf(
            KaraokeSyllable("Hel", 0, 100),
            KaraokeSyllable("lo ", 100, 200),
            KaraokeSyllable("— ", 200, 300),
            KaraokeSyllable("world", 300, 500),
        )

        assertEquals(listOf(syllables.take(2), listOf(syllables[2]), listOf(syllables[3])), groupIntoWords(syllables))
        assertEquals(emptyList(), groupIntoWords(emptyList()))
    }
}
