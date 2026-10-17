import com.alananasss.kittytune.ui.player.lyrics.LyricsScrolling
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LyricsPausedReadingPolicyTest {
    @Test
    fun `paused manual reading has no automatic return deadline`() {
        assertNull(LyricsScrolling.manualReadingDelayMs(lastManualMs = 1_000, nowMs = 1_500, isPlaying = false))
        assertNull(LyricsScrolling.manualReadingDelayMs(lastManualMs = 1_000, nowMs = 900_000, isPlaying = false))
    }

    @Test
    fun `a fresh paused view still places the current line without manual input`() {
        assertEquals(0L, LyricsScrolling.manualReadingDelayMs(lastManualMs = 0, nowMs = 900_000, isPlaying = false))
    }

    @Test
    fun `playing keeps the normal reading grace and expired input does not stall following`() {
        assertEquals(2_500L, LyricsScrolling.manualReadingDelayMs(lastManualMs = 1_000, nowMs = 1_500, isPlaying = true))
        assertEquals(0L, LyricsScrolling.manualReadingDelayMs(lastManualMs = 1_000, nowMs = 5_000, isPlaying = true))
    }

    @Test
    fun `clearing the manual timestamp on explicit seek follows while paused`() {
        assertNull(LyricsScrolling.manualReadingDelayMs(lastManualMs = 1_000, nowMs = 5_000, isPlaying = false))
        assertEquals(0L, LyricsScrolling.manualReadingDelayMs(lastManualMs = 0, nowMs = 5_000, isPlaying = false))
    }
}
