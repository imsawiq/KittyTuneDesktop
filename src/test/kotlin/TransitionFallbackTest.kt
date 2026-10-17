import com.alananasss.kittytune.audio.automix.TransitionFallback
import org.junit.Test
import kotlin.test.assertEquals

class TransitionFallbackTest {
    @Test fun `automix retains a fallback when ordinary crossfade is disabled`() {
        assertEquals(3_000L, TransitionFallback.durationMs(false, true, 0))
        assertEquals(7_000L, TransitionFallback.durationMs(false, true, 7))
    }

    @Test fun `disabled mixing and explicitly zero crossfade remain disabled`() {
        assertEquals(0L, TransitionFallback.durationMs(false, false, 7))
        assertEquals(0L, TransitionFallback.durationMs(true, false, 0))
        assertEquals(0L, TransitionFallback.durationMs(true, false, -1))
    }
}
