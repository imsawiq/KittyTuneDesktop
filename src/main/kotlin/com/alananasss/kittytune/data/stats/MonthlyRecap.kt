package com.alananasss.kittytune.data.stats

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** A completed local calendar month: start inclusive, end exclusive. Calendar arithmetic preserves DST. */
internal data class MonthlyRecapWindow(val month: YearMonth, val startMs: Long, val endMs: Long)

internal object MonthlyRecap {
    fun previousCompletedMonth(nowMs: Long, zone: ZoneId): MonthlyRecapWindow {
        val current = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(zone))
        val previous = current.minusMonths(1)
        return MonthlyRecapWindow(
            month = previous,
            startMs = previous.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            endMs = current.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }
}
