import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import com.alananasss.kittytune.ui.player.PinnedEffectsDragNotification
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PinnedEffectsDragLifecycleTest {
    @Test
    fun `removing a dragged grid releases parent scroll and allows another drag`() {
        var visible by mutableStateOf(true)
        var dragging by mutableStateOf(false)
        var parentScrollLocked = false
        val scene = ImageComposeScene(width = 100, height = 100, coroutineContext = Dispatchers.Unconfined) {
            if (visible) PinnedEffectsDragNotification(dragging) { parentScrollLocked = it }
        }
        var time = 0L
        fun frame() { time += 16_000_000L; scene.render(time).close() }
        try {
            frame()
            assertFalse(parentScrollLocked)
            dragging = true
            frame()
            assertTrue(parentScrollLocked)
            visible = false
            frame()
            assertFalse(parentScrollLocked, "Disposing the grid must restore parent scrolling")
            visible = true
            frame()
            assertTrue(parentScrollLocked)
            dragging = false
            frame()
            assertFalse(parentScrollLocked)
        } finally {
            scene.close()
        }
        assertFalse(parentScrollLocked)
    }
}
