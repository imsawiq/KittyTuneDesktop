package com.alananasss.kittytune.ui.player.lyrics

/** Untimed lyrics paced at the same speed as the full lyrics page. */
internal fun pacedPlainLyrics(text: String, speed: Float): List<LyricLine> {
    val lineMs = (1000f / (LyricsScrolling.PLAIN_BASE_LINES_PER_SEC * speed.coerceAtLeast(0.1f)))
        .toLong().coerceAtLeast(400L)
    return LyricsUtils.cleanPlainLyrics(text).lines().mapIndexed { index, raw ->
        val line = raw.trim()
        LyricLine(line, index * lineMs, (index + 1) * lineMs, isInstrumental = line.isEmpty())
    }
}

/** The same quick word reveal used by the full lyrics page, retaining genuine word timestamps. */
internal fun LyricLine.withRevealedWords(fallbackEndMs: Long? = null): LyricLine {
    if (words.isNotEmpty() || text.isBlank()) return this
    val end = when {
        endTime > startTime -> endTime
        fallbackEndMs != null && fallbackEndMs > startTime -> fallbackEndMs
        else -> startTime + 4_000L
    }
    val startInt = startTime.coerceIn(0, Int.MAX_VALUE.toLong() - 1).toInt()
    val endInt = end.coerceIn(startInt.toLong() + 1, Int.MAX_VALUE.toLong()).toInt()
    val revealed = revealSyllables(text, startInt, endInt).map {
        LyricWord(it.content, it.start.toLong(), it.end.toLong())
    }
    return copy(words = revealed, endTime = end)
}
