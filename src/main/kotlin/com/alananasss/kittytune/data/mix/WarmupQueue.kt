package com.alananasss.kittytune.data.mix

import com.alananasss.kittytune.domain.Track
import kotlin.math.roundToInt

/** Order music along an energy ramp, using measured audio features when available. */
internal object WarmupQueue {
    enum class Direction { RISING, FALLING }

    data class Candidate(val track: Track, val energy: Float?, val bpm: Float?) {
        val intensity: Float get() {
            val measured = energy?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            val tags = "${track.genre.orEmpty()} ${track.tagList.orEmpty()}".lowercase()
            val estimated = when {
                listOf("ambient", "chill", "sleep", "piano", "lofi", "lo-fi").any(tags::contains) -> 0.2f
                listOf("hardcore", "metal", "rage", "phonk", "dubstep", "hardstyle").any(tags::contains) -> 0.85f
                else -> 0.5f
            }
            val tempo = bpm?.takeIf { it.isFinite() && it > 0f }
                ?.let { ((it - 60f) / 140f).coerceIn(0f, 1f) }
            return (measured ?: estimated) * 0.8f + (tempo ?: measured ?: estimated) * 0.2f
        }
    }

    fun build(candidates: List<Candidate>, direction: Direction, limit: Int = 50): List<Track> {
        if (limit <= 0) return emptyList()
        val sorted = candidates.distinctBy { it.track.id }.sortedBy { it.intensity }
        val count = limit.coerceAtMost(sorted.size)
        val selected = when {
            count == 0 -> emptyList()
            count == 1 -> listOf(sorted.first())
            count == sorted.size -> sorted
            else -> (0 until count).map { index ->
                sorted[(index.toDouble() * (sorted.lastIndex) / (count - 1)).roundToInt()]
            }
        }
        val ordered = if (direction == Direction.FALLING) selected.asReversed() else selected
        return ordered.map { it.track }
    }
}
