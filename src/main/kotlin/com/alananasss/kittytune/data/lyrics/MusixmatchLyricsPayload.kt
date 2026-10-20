package com.alananasss.kittytune.data.lyrics

import com.alananasss.kittytune.data.network.MxmRichSyncLine
import com.alananasss.kittytune.data.network.MxmSubtitleLine
import com.alananasss.kittytune.ui.player.lyrics.LyricLine
import com.alananasss.kittytune.ui.player.lyrics.LyricSinger
import com.alananasss.kittytune.ui.player.lyrics.LyricWord
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Parses snapshots from endpoints requested for one fixed Musixmatch track ID. */
internal object MusixmatchLyricsPayload {
    private val gson = Gson()

    fun decode(
        responses: MusixmatchLyricsRequests.Responses,
        durationMs: Long,
        translationMap: Map<String, String> = emptyMap(),
        romanizationMap: Map<String, String> = emptyMap(),
    ): Pair<List<LyricLine>, String?> {
        val subtitleRes = responses.subtitle
        val richSyncRes = responses.richSync
        val plainRes = responses.plain

        val plainText = plainRes?.message?.body?.lyrics?.lyricsBody?.replace("******* This Lyrics is NOT for Commercial use *******", "")?.trim()
        val subtitleJson = subtitleRes?.message?.body?.subtitle?.subtitleBody
        val richSyncJson = richSyncRes?.message?.body?.richsync?.richsyncBody

        val lines = mutableListOf<LyricLine>()

        val mxmLines: List<MxmSubtitleLine> = try {
            if (!subtitleJson.isNullOrBlank() && subtitleJson.trim().startsWith("[")) {
                gson.fromJson(subtitleJson, object : TypeToken<List<MxmSubtitleLine>>() {}.type) ?: emptyList()
            } else emptyList()
        } catch (e: Exception) { emptyList() }

        val mxmRichLines: List<MxmRichSyncLine> = try {
            if (!richSyncJson.isNullOrBlank() && richSyncJson.trim().startsWith("[")) {
                gson.fromJson(richSyncJson, object : TypeToken<List<MxmRichSyncLine>>() {}.type) ?: emptyList()
            } else emptyList()
        } catch (e: Exception) { emptyList() }

        val trackRes = responses.track
        val trackDetail = trackRes?.message?.body?.track
        val performerTagging = trackDetail?.performerTagging
        val parts = performerTagging?.content.orEmpty()
        val leadArtistId = trackDetail?.artistId
        val leadArtistName = trackDetail?.artistName

        val partSingers = if (parts.isNotEmpty()) {
            val allArtists = parts.flatMap { it.performers.orEmpty() }
                .filter { it.type != "fan_chant" }
                .mapNotNull { it.fqid }
                .distinct()

            val leadFqid = performerTagging?.resources?.artists?.firstOrNull {
                (leadArtistId != null && it.artistId == leadArtistId) ||
                (leadArtistName != null && it.artistName.equals(leadArtistName, ignoreCase = true))
            }?.let { "mxm:artist:${it.artistId}" } ?: allArtists.firstOrNull()

            val secondFqid = allArtists.firstOrNull { it != leadFqid }

            parts.map { part ->
                val performers = part.performers.orEmpty()
                when {
                    performers.isEmpty() -> LyricSinger.DEFAULT
                    performers.any { it.type == "fan_chant" } || performers.size > 1 -> LyricSinger.BOTH
                    performers[0].fqid == leadFqid -> LyricSinger.SINGER_1
                    performers[0].fqid == secondFqid -> LyricSinger.SINGER_2
                    else -> if (allArtists.size > 1) LyricSinger.SINGER_2 else LyricSinger.SINGER_1
                }
            }
        } else emptyList()

        var currentPartIdx = 0
        fun findSingerForLine(lineText: String): LyricSinger {
            if (parts.isEmpty()) return LyricSinger.DEFAULT
            val cleaned = lineText.trim().lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), "")
            if (cleaned.isBlank()) return LyricSinger.DEFAULT

            for (offset in parts.indices) {
                val idx = (currentPartIdx + offset) % parts.size
                val snippetClean = parts[idx].snippet.orEmpty().lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), "")
                if (snippetClean.isNotBlank() && (snippetClean.contains(cleaned) || cleaned.contains(snippetClean) || (cleaned.length >= 6 && snippetClean.length >= 6 && (snippetClean.startsWith(cleaned.take(6)) || cleaned.startsWith(snippetClean.take(6)))))) {
                    currentPartIdx = idx
                    return partSingers.getOrElse(idx) { LyricSinger.DEFAULT }
                }
            }
            return LyricSinger.DEFAULT
        }

        if (mxmRichLines.isNotEmpty()) {
            for (rLine in mxmRichLines) {
                val lineText = rLine.x ?: ""
                if (lineText.isBlank()) continue

                val startMs = (rLine.ts * 1000).toLong()
                val endMs = (rLine.te * 1000).toLong()

                val words = rLine.l?.mapIndexed { index, w ->
                    val wordStartMs = startMs + (w.o * 1000).toLong()
                    val wordEndMs = if (index < rLine.l.size - 1) startMs + (rLine.l[index + 1].o * 1000).toLong() else endMs
                    LyricWord(w.c ?: "", wordStartMs, wordEndMs)
                } ?: emptyList()

                val translationText = translationMap[lineText.trim()]
                val romanizationText = romanizationMap[lineText.trim()]
                val singer = findSingerForLine(lineText)
                lines.add(LyricLine(lineText, startMs, endMs, words, translationText, romanizationText, singer))
            }
        } else if (mxmLines.isNotEmpty()) {
            for (i in mxmLines.indices) {
                val sub = mxmLines[i]
                val lineText = sub.text ?: ""
                if (lineText.isBlank()) continue

                val startMs = ((sub.time?.total ?: 0f) * 1000).toLong()
                val endMs = if (i < mxmLines.size - 1) ((mxmLines[i + 1].time?.total ?: 0f) * 1000).toLong() else durationMs

                val translationText = translationMap[lineText.trim()]
                val romanizationText = romanizationMap[lineText.trim()]
                val singer = findSingerForLine(lineText)
                lines.add(LyricLine(lineText, startMs, endMs, emptyList(), translationText, romanizationText, singer))
            }
        }

        return Pair(lines, plainText)
    }

}
