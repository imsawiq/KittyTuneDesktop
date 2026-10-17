import com.alananasss.kittytune.ui.player.movePinnedEffect
import org.junit.Test
import kotlin.test.assertEquals

class PinnedEffectOrderTest {
    @Test
    fun `drag moves the effect in either direction without duplicating it`() {
        assertEquals(listOf("b", "c", "a"), movePinnedEffect(listOf("a", "b", "c"), "a", 2))
        assertEquals(listOf("c", "a", "b"), movePinnedEffect(listOf("a", "b", "c"), "c", 0))
    }

    @Test
    fun `outside slots clamp to the ends and missing effects leave order intact`() {
        val order = listOf("a", "b", "c")
        assertEquals(order, movePinnedEffect(order, "missing", 0))
        assertEquals(listOf("b", "c", "a"), movePinnedEffect(order, "a", 500))
        assertEquals(listOf("c", "a", "b"), movePinnedEffect(order, "c", -500))
        assertEquals(emptyList(), movePinnedEffect(emptyList(), "a", 0))
    }
}
