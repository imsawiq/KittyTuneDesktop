package com.alananasss.kittytune.ui.player

import com.google.gson.Gson

/** Per-track choices in the existing lyrics_overrides preference namespace. */
internal class LyricsOverrideStore(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    private val gson = Gson()

    fun put(trackId: Long, result: UnifiedLyricResult) {
        val imported = result.takeIf { it.provider == "LOCAL" } ?: getImported(trackId)
        if (imported != null) {
            write("imported_$trackId", gson.toJson(imported))
        }
        write("override_$trackId", gson.toJson(result))
    }

    fun get(trackId: Long): UnifiedLyricResult? = readResult("override_$trackId")

    /** Keep an import available even after another search result is selected. */
    fun getImported(trackId: Long): UnifiedLyricResult? =
        readResult("imported_$trackId")?.takeIf { it.provider == "LOCAL" }
            ?: get(trackId)?.takeIf { it.provider == "LOCAL" }

    private fun readResult(key: String): UnifiedLyricResult? {
        val raw = read(key)?.takeIf { it.isNotBlank() } ?: return null
        return try {
            gson.fromJson(raw, UnifiedLyricResult::class.java)
        } catch (_: com.google.gson.JsonParseException) {
            null
        }
    }
}
