package com.alananasss.kittytune.data.together

/** Positions on the wire are milliseconds. Reject unbounded clocks/overflow before seeking a player. */
internal object TogetherPlaybackPolicy {
    private const val MAX_UNKNOWN_DURATION_MS = 24 * 60 * 60 * 1000L

    fun position(positionMs: Long, durationMs: Long?, flightMs: Long = 0L): Long {
        val limit = durationMs?.takeIf { it > 0 } ?: MAX_UNKNOWN_DURATION_MS
        val position = positionMs.coerceIn(0L, limit)
        val flight = flightMs.coerceIn(0L, 3_000L)
        return position + flight.coerceAtMost(limit - position)
    }

    fun acceptsHost(liveHost: String?, sender: String): Boolean = liveHost == null || liveHost == sender
}
