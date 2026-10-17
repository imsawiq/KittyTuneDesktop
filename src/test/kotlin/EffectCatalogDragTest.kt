import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.alananasss.kittytune.ui.player.EffectCatalogDragState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EffectCatalogDragTest {
    @Test
    fun `only a drop inside the right panel adds an effect`() {
        val drag = EffectCatalogDragState().apply { targetBounds = Rect(400f, 0f, 700f, 500f) }
        drag.start("reverb", Offset(100f, 100f))
        assertFalse(drag.isOverTarget)
        drag.move(Offset(400f, 0f))
        assertTrue(drag.isOverTarget)
        assertEquals("reverb", drag.finish())
        assertNull(drag.draggedId)
        drag.start("reverb", Offset(100f, 100f))
        assertNull(drag.finish())
    }

    @Test
    fun `hidden targets and cancelled gestures never add an effect`() {
        val drag = EffectCatalogDragState()
        drag.start("rain", Offset(100f, 100f))
        assertNull(drag.finish())
        drag.targetBounds = Rect(0f, 0f, 300f, 300f)
        drag.start("rain", Offset(100f, 100f))
        drag.cancel()
        assertNull(drag.finish())
    }
}
