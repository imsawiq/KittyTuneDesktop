import com.alananasss.kittytune.ui.player.queuePlayNextDestination
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueOrderingTest {
    @Test
    fun `upcoming occurrence moves next without duplicating the track`() {
        val queue = mutableListOf("playing", "same", "other", "same")
        val destination = queuePlayNextDestination(queue.size, currentIndex = 0, selectedIndex = 3)!!

        queue.add(destination, queue.removeAt(3))

        assertEquals(listOf("playing", "same", "same", "other"), queue)
        assertEquals(4, queue.size)
    }

    @Test
    fun `past occurrence goes after current track accounting for its shifted index`() {
        val queue = mutableListOf("past", "other", "playing", "upcoming")
        val destination = queuePlayNextDestination(queue.size, currentIndex = 2, selectedIndex = 0)!!

        queue.add(destination, queue.removeAt(0))

        assertEquals(listOf("other", "playing", "past", "upcoming"), queue)
    }

    @Test
    fun `already next occurrence stays next`() {
        assertEquals(2, queuePlayNextDestination(4, currentIndex = 1, selectedIndex = 2))
    }

    @Test
    fun `playing occurrence and stale indices cannot be moved next`() {
        assertNull(queuePlayNextDestination(4, currentIndex = 1, selectedIndex = 1))
        assertNull(queuePlayNextDestination(4, currentIndex = 1, selectedIndex = -1))
        assertNull(queuePlayNextDestination(4, currentIndex = 1, selectedIndex = 4))
        assertNull(queuePlayNextDestination(0, currentIndex = -1, selectedIndex = 0))
    }
}
