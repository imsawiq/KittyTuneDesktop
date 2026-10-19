package com.alananasss.kittytune.data.mix

import com.alananasss.kittytune.domain.Track
import kotlin.math.roundToInt

/** Order music along an energy ramp, using measured audio features when available. */
internal object WarmupQueue {
    enum class Direction { RISING, FALLING }

    data class Candidate(val track: Track, val energy: Float?, val bpm: Float?) {
        val intensity: Float get() {
            val measured = energy?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            val estimated = estimateGenreIntensity(track.genre)
                ?: estimateGenreIntensity(track.tagList)
                ?: 0.5f
            val tempo = bpm?.takeIf { it.isFinite() && it > 0f }
                ?.let { ((it - 60f) / 140f).coerceIn(0f, 1f) }
            return (measured ?: estimated) * 0.8f + (tempo ?: measured ?: estimated) * 0.2f
        }
    }

    private val calmGenres = Regex(
        "(?<![\\p{L}\\p{N}])(?:ambient|chill|sleep|piano|lofi|lo[- ]fi)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )
    private val energeticGenres = Regex(
        "(?<![\\p{L}\\p{N}])(?:hardcore|metal|rage|phonk|dubstep|hardstyle)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )

    private fun estimateGenreIntensity(value: String?): Float? {
        if (value.isNullOrBlank()) return null
        val calm = calmGenres.containsMatchIn(value)
        val energetic = energeticGenres.containsMatchIn(value)
        return when {
            calm && energetic -> (0.2f + 0.85f) / 2f
            calm -> 0.2f
            energetic -> 0.85f
            else -> null
        }
    }

    fun build(candidates: List<Candidate>, direction: Direction, limit: Int = 50): List<Track> {
        if (limit <= 0) return emptyList()
        val sorted = candidates.distinctBy { it.track.id }.sortedBy { it.intensity }
        val count = limit.coerceAtMost(sorted.size)
        val selected = when {
            count == 0 -> emptyList()
            count == 1 -> listOf(if (direction == Direction.RISING) sorted.first() else sorted.last())
            count == sorted.size -> sorted
            else -> (0 until count).map { index ->
                sorted[(index.toDouble() * (sorted.lastIndex) / (count - 1)).roundToInt()]
            }
        }
        val ordered = if (direction == Direction.FALLING) selected.asReversed() else selected
        return ordered.map { it.track }
    }
}
