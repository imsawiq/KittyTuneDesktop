package com.alananasss.kittytune.data.artist

import com.alananasss.kittytune.data.lyrics.GeniusVoices
import com.alananasss.kittytune.domain.Track

/** A missing/local credit must not resolve to an unrelated artist named "Unknown Artist". */
internal fun listenerLookupArtist(track: Track, unknownArtistLabel: String): String? {
    if (track.source == "local" || (track.id < 0 && track.source != "youtube")) return null
    val name = track.displayArtist.trim()
    if (name.isBlank() || name.equals(unknownArtistLabel, ignoreCase = true) ||
        name.equals("Unknown Artist", ignoreCase = true)
    ) return null
    return GeniusVoices.splitNames(name).firstOrNull()
}
