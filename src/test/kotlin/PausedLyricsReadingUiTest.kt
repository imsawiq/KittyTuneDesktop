import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.alananasss.kittytune.ui.player.lyrics.FollowActiveLine
import com.alananasss.kittytune.ui.player.lyrics.accompanist.KaraokeLyricsView
import com.alananasss.kittytune.ui.player.lyrics.lyricsWheel
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PausedLyricsReadingUiTest {
    private fun verifyPausedReading(enhanced: Boolean) {
        lateinit var state: LazyListState
        var position by mutableStateOf(10_000L)
        val lines = SyncedLyrics((0 until 100).map {
            SyncedLine(content = "Lyric line $it", translation = null, start = it * 1_000, end = (it + 1) * 1_000)
        })
        val scene = ImageComposeScene(width = 400, height = 220, density = Density(1f)) {
            MaterialTheme {
                state = rememberLazyListState()
                if (enhanced) {
                    KaraokeLyricsView(
                        listState = state,
                        lyrics = lines,
                        currentPosition = { position.toInt() },
                        isPlaying = false,
                        seekPositionMs = position,
                        onLineClicked = {},
                        onLinePressed = {},
                        useBlurEffect = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val scope = rememberCoroutineScope()
                    var manualMs by remember { mutableStateOf(0L) }
                    FollowActiveLine(
                        listState = state,
                        activeIndex = (position / 1_000).toInt(),
                        isPlaying = false,
                        positionMs = position,
                        manualScrollMs = manualMs,
                    )
                    LazyColumn(
                        state = state,
                        modifier = Modifier.fillMaxSize().lyricsWheel(state, scope, { 3f }) {
                            manualMs = System.currentTimeMillis()
                        },
                    ) {
                        items((0 until 100).toList()) { Box(Modifier.fillMaxWidth().height(30.dp)) }
                    }
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
        try {
            frames(60)
            val original = state.firstVisibleItemIndex
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(180f, 120f), Offset(0f, 1f))
            frames(60)
            val manual = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
            assertTrue(manual.first > original, "The reader must be able to move away from the paused line")
            runBlocking { delay(3_250) }
            frames(80)
            assertEquals(manual, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
            position = 50_000L
            frames(100)
            assertTrue(state.layoutInfo.visibleItemsInfo.any { it.index == 50 }, "A paused seek must follow its new line")
            assertTrue(state.firstVisibleItemIndex > 40)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `classic lyrics stay where paused reader scrolled until an explicit seek`() = verifyPausedReading(false)

    @Test
    fun `enhanced lyrics stay where paused reader scrolled until an explicit seek`() = verifyPausedReading(true)
}
