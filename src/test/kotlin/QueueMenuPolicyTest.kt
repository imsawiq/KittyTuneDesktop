package com.alananasss.kittytune.ui.player

import com.alananasss.kittytune.domain.Track
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class QueueMenuPolicyTest {
    private fun track(id: Long, source: String = "soundcloud") =
        Track(id = id, title = "Track $id", artworkUrl = null, durationMs = 120_000, user = null, source = source)

    @Test
    fun `counts exclude history and current track and distinguish sources`() {
        val selected = track(1)
        val next = selected.copy()
        val queue = listOf(selected.copy(), selected, track(1, "youtube"), next, track(2), selected.copy())
        val occurrences = futureQueueOccurrences(queue, 1, selected)
        assertEquals(2, occurrences.size)
        assertSame(next, occurrences.first())
    }

    @Test
    fun `zero and one future occurrences insert then two arm repeat`() {
        assertEquals(QueueMenuAction.INSERT_NEXT, QueueMenuStatus(0, false, false).action)
        assertEquals(QueueMenuAction.INSERT_NEXT, QueueMenuStatus(1, false, false).action)
        assertEquals(QueueMenuAction.ARM_REPEAT, QueueMenuStatus(2, false, false).action)
        assertEquals(QueueMenuAction.ARM_REPEAT, QueueMenuStatus(5, false, false).action)
        assertEquals(QueueMenuAction.NONE, QueueMenuStatus(2, true, false).action)
        assertFalse(QueueMenuStatus(0, false, true).canAdd)
    }

    @Test
    fun `armed repeat waits for the exact future occurrence instead of repeating unrelated current music`() {
        val first = track(1)
        val target = track(2)
        val otherCopy = target.copy()
        val queue = listOf(first, otherCopy, target)
        assertEquals(QueuedRepeatDisposition.WAIT, queuedRepeatDisposition(target, queue, 0, first))
        assertEquals(QueuedRepeatDisposition.WAIT, queuedRepeatDisposition(target, queue, 1, otherCopy))
        assertEquals(QueuedRepeatDisposition.ACTIVATE, queuedRepeatDisposition(target, queue, 2, target.copy()))
        assertEquals(QueuedRepeatDisposition.WAIT, queuedRepeatDisposition(target, queue, 2, track(2, "youtube")))
    }

    @Test
    fun `removing or skipping past the armed occurrence cancels it while reordering keeps it`() {
        val first = track(1)
        val target = track(2)
        val later = track(3)
        assertEquals(QueuedRepeatDisposition.CANCEL, queuedRepeatDisposition(target, listOf(first, later), 0, first))
        assertEquals(QueuedRepeatDisposition.CANCEL, queuedRepeatDisposition(target, listOf(first, target, later), 2, later))
        assertEquals(QueuedRepeatDisposition.WAIT, queuedRepeatDisposition(target, listOf(first, later, target), 1, later))
    }
}
