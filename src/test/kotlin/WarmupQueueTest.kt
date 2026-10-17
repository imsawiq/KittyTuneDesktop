package com.alananasss.kittytune.data.mix

import com.alananasss.kittytune.domain.Track
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WarmupQueueTest {
    private fun candidate(id: Long, energy: Float) = WarmupQueue.Candidate(
        Track(id, "Track $id", null, 180_000L, null), energy, null,
    )

    @Test fun `rising and falling follow measured intensity`() {
        val tracks = listOf(candidate(3, .9f), candidate(1, .1f), candidate(2, .5f))
        assertEquals(listOf(1L, 2L, 3L), WarmupQueue.build(tracks, WarmupQueue.Direction.RISING).map { it.id })
        assertEquals(listOf(3L, 2L, 1L), WarmupQueue.build(tracks, WarmupQueue.Direction.FALLING).map { it.id })
    }

    @Test fun `fifty tracks cover the full available ramp without duplicates`() {
        val candidates = (0L..199L).map { candidate(it, it / 199f) }
        val result = WarmupQueue.build(candidates, WarmupQueue.Direction.RISING)
        assertEquals(50, result.size)
        assertEquals(50, result.distinctBy { it.id }.size)
        assertEquals(0L, result.first().id)
        assertEquals(199L, result.last().id)
        assertTrue(result.zipWithNext().all { (a, b) -> a.id < b.id })
    }

    @Test fun `short and empty libraries do not invent tracks`() {
        assertEquals(emptyList(), WarmupQueue.build(emptyList(), WarmupQueue.Direction.RISING))
        val candidate = candidate(1, Float.NaN)
        assertTrue(candidate.intensity.isFinite())
        assertEquals(listOf(candidate.track), WarmupQueue.build(listOf(candidate, candidate), WarmupQueue.Direction.RISING))
    }
}
