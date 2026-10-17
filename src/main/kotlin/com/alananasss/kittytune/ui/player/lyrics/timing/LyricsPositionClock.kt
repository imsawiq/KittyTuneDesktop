package com.alananasss.kittytune.ui.player.lyrics.timing

import kotlin.math.abs

/** Interpolate fresh playback reports, with a bounded estimate when buffering stops them. */
internal class LyricsPositionClock {
    private var report: Long? = null
    private var reportAt = 0L
    private var shown = 0f

    fun step(positionMs: Long, speed: Float, nowNanos: Long): Float {
        val position = positionMs.coerceAtLeast(0L)
        val rate = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        val previous = report
        if (previous == null || nowNanos < reportAt) {
            report = position
            reportAt = nowNanos
            shown = position.toFloat()
            return shown
        }
        var seek = false
        if (position != previous) {
            val expected = previous + elapsedMs(nowNanos) * rate
            seek = position < previous - 50L || abs(position - expected) > 200f
            // Every fresh report becomes the anchor. Keeping the first anchor while capping
            // its age at 400 ms froze word reveals even as later reports kept arriving.
            report = position
            reportAt = nowNanos
        }
        val estimate = position + elapsedMs(nowNanos) * rate
        shown = if (seek) estimate else maxOf(shown, estimate)
        return shown
    }

    private fun elapsedMs(nowNanos: Long): Float =
        ((nowNanos - reportAt).coerceIn(0L, MAX_EXTRAPOLATION_NANOS) / 1_000_000.0).toFloat()

    private companion object {
        const val MAX_EXTRAPOLATION_NANOS = 400_000_000L
    }
}
