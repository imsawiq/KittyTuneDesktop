package com.alananasss.kittytune.ui.player

import com.alananasss.kittytune.domain.Track
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class QueueMenuOccurrenceTest {
    private fun track(id: Long, source: String = "soundcloud") =
        Track(id = id, title = "Track $id", artworkUrl = null, durationMs = 120_000, user = null, source = source)

    @Test fun `a retained UI copy resolves to the live occurrence and moves without insertion`() {
        val playing = track(1)
        val selected = track(3)
        val queue = mutableListOf(playing, track(2), selected)
        val retainedUiCopy = selected.copy()
        val live = queueMenuOccurrence(queue, 2, retainedUiCopy)
        assertSame(selected, live)
        val destination = queuePlayNextDestination(queue.size, 0, 2)!!
        queue.add(destination, queue.removeAt(2))
        assertEquals(3, queue.size)
        assertSame(selected, queue[1])
    }

    @Test fun `duplicate tracks resolve by the selected slot rather than the first matching id`() {
        val first = track(2)
        val second = first.copy()
        val queue = listOf(track(1), first, second)
        assertSame(second, queueMenuOccurrence(queue, 2, first.copy()))
        assertSame(first, queueMenuOccurrence(queue, 1, first.copy()))
    }

    @Test fun `a stale slot source mismatch or removed occurrence cannot target a different track`() {
        val queue = listOf(track(1), track(2))
        assertNull(queueMenuOccurrence(queue, 1, track(3)))
        assertNull(queueMenuOccurrence(queue, 1, track(2, "youtube")))
        assertNull(queueMenuOccurrence(queue, -1, track(1)))
        assertNull(queueMenuOccurrence(queue, 2, track(2)))
    }
}
