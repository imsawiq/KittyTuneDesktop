package com.alananasss.kittytune.data.friends

import com.alananasss.kittytune.domain.Track
import com.alananasss.kittytune.domain.User
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class FriendPublicTrack(
    val id: Long, val title: String, val artist: String, val url: String,
    val artwork: String?, val source: String, val durationMs: Long, val albumTitle: String? = null,
) {
    fun toTrack() = Track(id, title, artwork, durationMs.takeIf { it > 0 }, User(0, artist, null),
        permalinkUrl = url, source = source, publisherMetadata = Track.PublisherMetadata(artist = artist, albumTitle = albumTitle))

    companion object {
        fun of(track: Track?): FriendPublicTrack? {
            track ?: return null
            if (track.sharing?.trim().equals("private", true) || !track.secretToken.isNullOrBlank()) return null
            val source = (track.source ?: "soundcloud").trim().lowercase(java.util.Locale.ROOT)
            if (source !in setOf("soundcloud", "spotify", "youtube", "youtube_music", "tidal", "qobuz", "deezer")) return null
            val url = publicUrl(track.permalinkUrl, allowYoutubeVideo = true) ?: return null
            val title = text(track.title, 200).takeIf { it.isNotBlank() } ?: return null
            return FriendPublicTrack(track.id, title, text(track.displayArtist, 160), url,
                publicUrl(track.artworkUrl), source,
                (track.durationMs ?: 0).coerceIn(0, 86_400_000),
                text(track.publisherMetadata?.albumTitle, 160).takeIf { it.isNotEmpty() })
        }

        fun publicUrl(raw: String?, allowYoutubeVideo: Boolean = false): String? {
            if (raw.isNullOrBlank() || raw.length > 1400) return null
            val url = raw.toHttpUrlOrNull() ?: return null
            val host = url.host.lowercase().trimEnd('.')
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null || url.port !in listOf(80, 443) ||
                !host.contains('.') || host.contains(':') || host.endsWith(".local") || host.endsWith(".localhost") ||
                host.endsWith(".internal") || host.endsWith(".lan") || host.endsWith(".home") || host.all { it.isDigit() || it == '.' } ||
                url.pathSegments.any { it.startsWith("s-") } || url.encodedPath.contains("secret", true)) return null
            if (url.query != null) {
                if (!allowYoutubeVideo || host !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com") ||
                    url.queryParameterNames != setOf("v") || !url.queryParameter("v").orEmpty().matches(Regex("[A-Za-z0-9_-]{11}"))) return null
            }
            return url.toString().takeIf { it.length <= 1400 }
        }

        fun text(raw: String?, limit: Int): String = raw.orEmpty().filter { !it.isISOControl() }.trim().take(limit)
    }
}
