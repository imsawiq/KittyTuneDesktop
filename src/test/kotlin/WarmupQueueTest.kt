package com.alananasss.kittytune.data.mix

import com.alananasss.kittytune.domain.Track
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WarmupQueueTest {
    private fun candidate(id: Long, energy: Float) = WarmupQueue.Candidate(
        Track(id, "Track $id", null, 180_000L, null), energy, null,
    )

    private fun estimatedCandidate(
        id: Long,
        genre: String? = null,
        tags: String? = null,
        bpm: Float? = null,
    ) = WarmupQueue.Candidate(
        Track(id, "Track $id", null, 180_000L, null, genre = genre, tagList = tags), null, bpm,
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

    @Test fun `genre fallback orders an unmeasured library in both directions`() {
        val candidates = listOf(
            estimatedCandidate(3, genre = "Hardcore"),
            estimatedCandidate(2, genre = "Pop"),
            estimatedCandidate(1, genre = "Ambient"),
        )
        assertEquals(listOf(1L, 2L, 3L), WarmupQueue.build(candidates, WarmupQueue.Direction.RISING).map { it.id })
        assertEquals(listOf(3L, 2L, 1L), WarmupQueue.build(candidates, WarmupQueue.Direction.FALLING).map { it.id })
    }

    @Test fun `genre names inside unrelated words do not change intensity`() {
        val neutral = estimatedCandidate(1).intensity
        listOf("UK Garage", "chilly", "sleepless", "металлургия", "rageous").forEach { genre ->
            assertEquals(neutral, estimatedCandidate(2, genre = genre).intensity, genre)
        }
    }

    @Test fun `quoted tags and lo fi variants supply missing genre estimates`() {
        val calm = estimatedCandidate(1, genre = "ambient").intensity
        listOf("lofi", "LO-FI", "lo fi", "\"lo fi\" beats", "#ambient").forEach { tags ->
            assertEquals(calm, estimatedCandidate(2, genre = "Other", tags = tags).intensity, tags)
        }
        assertEquals(
            estimatedCandidate(3, genre = "Metal").intensity,
            estimatedCandidate(4, tags = "\"heavy metal\" guitars").intensity,
        )
    }

    @Test fun `recognized genre takes precedence over conflicting tags`() {
        assertEquals(
            estimatedCandidate(1, genre = "Hardcore").intensity,
            estimatedCandidate(2, genre = "Hardcore", tags = "chill").intensity,
        )
        assertEquals(
            estimatedCandidate(3, genre = "Ambient").intensity,
            estimatedCandidate(4, genre = "Ambient", tags = "metal").intensity,
        )
    }

    @Test fun `conflicting tags are not classified as purely calm or aggressive`() {
        val intensity = estimatedCandidate(1, tags = "\"lo-fi\" metal").intensity
        assertTrue(intensity > estimatedCandidate(2, genre = "Ambient").intensity)
        assertTrue(intensity < estimatedCandidate(3, genre = "Metal").intensity)
    }

    @Test fun `tempo distinguishes otherwise unmeasured neutral tracks`() {
        val candidates = listOf(
            estimatedCandidate(3, bpm = 180f),
            estimatedCandidate(1, bpm = 80f),
            estimatedCandidate(2, bpm = 120f),
        )
        assertEquals(listOf(1L, 2L, 3L), WarmupQueue.build(candidates, WarmupQueue.Direction.RISING).map { it.id })
    }

    @Test fun `invalid tempo does not replace the genre estimate`() {
        val calm = estimatedCandidate(1, genre = "Ambient").intensity
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -120f).forEach { bpm ->
            assertEquals(calm, estimatedCandidate(2, genre = "Ambient", bpm = bpm).intensity)
        }
    }

    @Test fun `measured energy takes precedence over genre and tags`() {
        val calmMeasurement = estimatedCandidate(1, genre = "Metal", tags = "rage").copy(energy = 0.1f)
        val energeticMeasurement = estimatedCandidate(2, genre = "Ambient", tags = "sleep").copy(energy = 0.9f)
        assertEquals(candidate(1, 0.1f).intensity, calmMeasurement.intensity)
        assertEquals(candidate(2, 0.9f).intensity, energeticMeasurement.intensity)
        assertEquals(listOf(1L, 2L), WarmupQueue.build(
            listOf(energeticMeasurement, calmMeasurement), WarmupQueue.Direction.RISING,
        ).map { it.id })
    }

    @Test fun `single track limit starts at the requested end of the ramp`() {
        val candidates = listOf(candidate(2, .5f), candidate(3, .9f), candidate(1, .1f))
        assertEquals(listOf(1L), WarmupQueue.build(candidates, WarmupQueue.Direction.RISING, limit = 1).map { it.id })
        assertEquals(listOf(3L), WarmupQueue.build(candidates, WarmupQueue.Direction.FALLING, limit = 1).map { it.id })
        assertEquals(emptyList(), WarmupQueue.build(candidates, WarmupQueue.Direction.RISING, limit = 0))
    }
}
