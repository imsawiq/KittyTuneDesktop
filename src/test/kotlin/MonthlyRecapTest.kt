package com.alananasss.kittytune.data.stats

import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.assertEquals

class MonthlyRecapTest {
    private val helsinki = ZoneId.of("Europe/Helsinki")
    private fun window(now: String, zone: ZoneId = helsinki) = MonthlyRecap.previousCompletedMonth(Instant.parse(now).toEpochMilli(), zone)

    @Test
    fun `current partial month is excluded with exact local midnight boundaries`() {
        val recap = window("2026-10-17T09:00:00Z")
        assertEquals(YearMonth.of(2026, 9), recap.month)
        assertEquals(Instant.parse("2026-08-31T21:00:00Z").toEpochMilli(), recap.startMs)
        assertEquals(Instant.parse("2026-09-30T21:00:00Z").toEpochMilli(), recap.endMs)
    }

    @Test
    fun `January recaps December of the prior year`() {
        assertEquals(YearMonth.of(2025, 12), window("2026-01-15T12:00:00Z").month)
    }

    @Test
    fun `spring and autumn recap durations preserve daylight saving boundaries`() {
        val march = window("2026-04-01T12:00:00Z")
        val october = window("2026-11-01T12:00:00Z")
        assertEquals((31L * 24L - 1L) * 3_600_000L, march.endMs - march.startMs)
        assertEquals((31L * 24L + 1L) * 3_600_000L, october.endMs - october.startMs)
    }

    @Test
    fun `month rollover follows local timezone rather than UTC`() {
        val now = "2026-10-31T22:30:00Z"
        assertEquals(YearMonth.of(2026, 10), window(now).month)
        assertEquals(YearMonth.of(2026, 9), window(now, ZoneId.of("UTC")).month)
    }

    @Test
    fun `leap February remains a full completed calendar month`() {
        val february = window("2024-03-15T12:00:00Z", ZoneId.of("UTC"))
        assertEquals(29L * 24 * 3_600_000L, february.endMs - february.startMs)
    }
}
