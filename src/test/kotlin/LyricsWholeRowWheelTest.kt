import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.alananasss.kittytune.ui.player.lyrics.LyricsScrolling
import com.alananasss.kittytune.ui.player.lyrics.lyricsWheel
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsWholeRowWheelTest {
    private fun afterWheel(
        first: Int = 0,
        initialOffset: Int = 0,
        notches: List<Float> = listOf(1f),
        variableRows: Boolean = true,
        rows: Int = 100,
    ): Pair<Int, Int> {
        lateinit var state: LazyListState
        val scene = ImageComposeScene(width = 300, height = 160, density = Density(1f)) {
            state = rememberLazyListState(first, initialOffset)
            val scope = rememberCoroutineScope()
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize().lyricsWheel(state, scope, { 3f }, {}),
            ) {
                items((0 until rows).toList()) { index ->
                    val height = if (variableRows) listOf(23, 51, 34)[index % 3] else 20
                    Box(Modifier.fillMaxWidth().height(height.dp))
                }
            }
        }
        var time = 0L
        fun frames(count: Int) {
            repeat(count) {
                time += 16_000_000L
                scene.render(time).close()
            }
        }
        return try {
            frames(2)
            notches.forEach {
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(100f, 80f), Offset(0f, it))
                frames(4)
            }
            frames(50)
            state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        } finally {
            scene.close()
        }
    }

    @Test
    fun `notch settles on a complete variable-height row from a partial row`() {
        assertEquals(6 to 0, afterWheel(first = 3, initialOffset = 11))
        assertEquals(7 to 0, afterWheel(first = 10, initialOffset = 13, notches = listOf(-1f)))
    }

    @Test
    fun `rapid notches add lyric rows while the previous slide is running`() {
        assertEquals(6 to 0, afterWheel(notches = listOf(1f, 1f)))
    }

    @Test
    fun `precise fractional gestures keep their smooth sub-row distance`() {
        assertEquals(0 to 15, afterWheel(notches = listOf(0.25f), variableRows = false))
        assertFalse(LyricsScrolling.isWholeWheelNotch(0.25f))
        assertTrue(LyricsScrolling.isWholeWheelNotch(1f))
    }

    @Test
    fun `discrete wheel preferences round to complete rows`() {
        assertEquals(1, LyricsScrolling.wheelRowDelta(1f, 0.5f))
        assertEquals(-6, LyricsScrolling.wheelRowDelta(-2f, 3f))
        assertEquals(0, LyricsScrolling.wheelRowDelta(0f, 3f))
    }
}
