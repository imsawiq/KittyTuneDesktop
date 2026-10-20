package com.alananasss.kittytune.data.artist

import com.alananasss.kittytune.domain.Playlist
import com.alananasss.kittytune.domain.Track
import java.net.URI
import java.util.Locale

/** Provider identities and explicit membership determine which songs are already represented by a record. */
internal object ReleaseCatalog {
    fun unlikedTracks(tracks: List<Track>, likes: List<Track>): List<Track> {
        val likedKeys = likes.mapTo(HashSet(), ::trackKey)
        return tracks.distinctBy(::trackKey).filter { trackKey(it) !in likedKeys }
    }

    private fun trackKey(track: Track): String = "${track.source ?: "soundcloud"}:${track.id}"

    fun recordKey(record: Playlist): String = record.urn?.takeIf { it.isNotBlank() }
        ?: record.permalinkUrl?.takeIf { it.isNotBlank() }
        ?: "soundcloud:playlists:${record.id}"

    fun belongsTo(track: Track, record: Playlist): Boolean {
        if (record.tracks.orEmpty().any { it.id == track.id && (it.source ?: "soundcloud") == (track.source ?: "soundcloud") }) return true
        // Album IDs and titles belong to a provider's catalog. Identical numeric IDs, or
        // identically named records on another service, do not prove membership.
        if (track.source.orEmpty().ifBlank { "soundcloud" }.lowercase(Locale.ROOT) != recordProvider(record)) return false
        val albumId = track.publisherMetadata?.albumId?.takeIf { it.isNotBlank() }
        if (albumId != null) {
            return albumId in listOfNotNull(record.permalink, record.urn?.substringAfterLast(':'), record.id.toString())
        }
        val albumTitle = track.publisherMetadata?.albumTitle?.let(::normalized)?.takeIf { it.isNotEmpty() } ?: return false
        if (albumTitle != normalized(record.title.orEmpty())) return false
        val trackArtist = normalized(track.displayArtist.ifBlank { track.user?.username.orEmpty() })
        val recordArtist = normalized(record.user?.username.orEmpty())
        return trackArtist.isNotEmpty() && recordArtist.isNotEmpty() && trackArtist == recordArtist
    }

    private fun recordProvider(record: Playlist): String {
        val urnProvider = record.urn?.substringBefore(':')?.lowercase(Locale.ROOT)
        if (urnProvider != null && urnProvider in setOf("spotify", "qobuz", "tidal", "deezer", "soundcloud")) return urnProvider
        val host = runCatching { URI(record.permalinkUrl.orEmpty()).host?.lowercase(Locale.ROOT) }.getOrNull()
        return when (host) {
            "open.spotify.com" -> "spotify"
            "www.qobuz.com", "qobuz.com" -> "qobuz"
            "tidal.com", "www.tidal.com", "listen.tidal.com" -> "tidal"
            "deezer.com", "www.deezer.com" -> "deezer"
            else -> "soundcloud"
        }
    }

    private fun normalized(value: String): String = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
