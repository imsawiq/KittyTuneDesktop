package com.alananasss.kittytune.ui.player.lyrics.editor

/** Unsaved authoring state. Invalid/partial time input stays editable and cannot be exported. */
internal data class LyricsTimingDraft(val lines: List<Line>) {
    enum class Mode { LINE, WORD }
    data class Word(val text: String, val time: String = "")
    data class Line(val text: String, val time: String = "", val words: List<Word>)
    data class Cell(val line: Int, val word: Int?, val text: String, val time: String)

    fun cells(mode: Mode): List<Cell> = lines.flatMapIndexed { index, line ->
        if (mode == Mode.LINE) listOf(Cell(index, null, line.text, line.time))
        else line.words.mapIndexed { word, cue -> Cell(index, word, cue.text, cue.time) }
    }

    fun withTime(cell: Cell, time: String): LyricsTimingDraft = copy(lines = lines.mapIndexed { index, line ->
        if (index != cell.line) line
        else if (cell.word == null) line.copy(time = time)
        else line.copy(words = line.words.mapIndexed { word, cue -> if (word == cell.word) cue.copy(time = time) else cue })
    })

    fun isComplete(mode: Mode, durationMs: Long): Boolean {
        val cells = cells(mode)
        if (cells.isEmpty()) return false
        var previous = -1L
        for (cell in cells) {
            val time = parseTime(cell.time) ?: return false
            if (time <= previous || (durationMs > 0 && time >= durationMs)) return false
            previous = time
        }
        return true
    }

    fun exportLrc(mode: Mode, durationMs: Long): String {
        require(isComplete(mode, durationMs)) { "Every timestamp must increase and fit the track" }
        return lines.joinToString("\n") { line ->
            if (mode == Mode.LINE) "[${formatTime(requireNotNull(parseTime(line.time)))}]${line.text}"
            else {
                val start = requireNotNull(parseTime(line.words.first().time))
                "[${formatTime(start)}]" + line.words.joinToString("") { word ->
                    "<${formatTime(requireNotNull(parseTime(word.time)))}>${word.text}"
                }
            }
        }
    }

    companion object {
        private val timestamp = Regex("(\\d{1,4}):(\\d{2})(?:[.,](\\d{1,3}))?")
        fun parseTime(raw: String): Long? {
            val match = timestamp.matchEntire(raw.trim()) ?: return null
            val minutes = match.groupValues[1].toLongOrNull() ?: return null
            val seconds = match.groupValues[2].toLongOrNull()?.takeIf { it < 60 } ?: return null
            val millis = match.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0L
            return minutes * 60_000 + seconds * 1000 + millis
        }
        fun formatTime(ms: Long): String {
            require(ms >= 0)
            return "%02d:%02d.%03d".format(java.util.Locale.ROOT, ms / 60_000, (ms / 1000) % 60, ms % 1000)
        }
        fun canPrepare(text: String): Boolean = text.length <= 200_000 &&
            text.isNotBlank() && text.lineSequence().count { it.isNotBlank() } <= 2_000

        fun fromPlain(text: String): LyricsTimingDraft {
            require(canPrepare(text)) { "Lyrics must contain at most 2000 lines and 200000 characters" }
            return LyricsTimingDraft(text.lines().map { it.trim() }.filter { it.isNotBlank() }.map { line ->
                Line(line, words = Regex("\\S+\\s*").findAll(line).map { Word(it.value) }.toList())
            })
        }
    }
}
