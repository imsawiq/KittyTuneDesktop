import com.alananasss.kittytune.ui.player.lyrics.panelLyricsFontSize
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PanelLyricsTypographyTest {
    @Test
    fun `roomy and unbounded panels preserve the user font size`() {
        assertEquals(22f, panelLyricsFontSize(22f, 400f, 600f))
        assertEquals(22f, panelLyricsFontSize(22f, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `both narrower and shorter panels reduce font size`() {
        assertTrue(panelLyricsFontSize(22f, 240f, 600f) < 22f)
        assertTrue(panelLyricsFontSize(22f, 400f, 190f) < 22f)
    }

    @Test
    fun `small fonts stay readable and large settings stay proportional`() {
        assertEquals(12f, panelLyricsFontSize(14f, 100f, 100f))
        assertEquals(10f, panelLyricsFontSize(10f, 100f, 100f))
        assertEquals(40f * 0.72f, panelLyricsFontSize(40f, 100f, 100f))
    }
}
