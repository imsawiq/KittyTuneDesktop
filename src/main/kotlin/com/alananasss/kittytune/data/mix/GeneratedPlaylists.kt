package com.alananasss.kittytune.data.mix

import com.alananasss.kittytune.core.str
import com.alananasss.kittytune.data.local.AppDatabase
import com.alananasss.kittytune.domain.Track
import java.util.concurrent.atomic.AtomicLong

/** Materialize generated mixes as ordinary editable local playlists in one transaction. */
internal object GeneratedPlaylists {
    private val nextId = AtomicLong(-System.currentTimeMillis())

    suspend fun create(title: String, tracks: List<Track>, cover: String? = null): Long {
        require(title.isNotBlank() && tracks.isNotEmpty())
        var id = nextId.decrementAndGet()
        while (AppDatabase.downloadDao.getPlaylist(id) != null) id = nextId.decrementAndGet()
        AppDatabase.execTogether(*statements(id, title, str("me_artist"), tracks, cover, System.currentTimeMillis()).toTypedArray())
        return id
    }

    internal fun statements(
        id: Long,
        title: String,
        artist: String,
        tracks: List<Track>,
        cover: String?,
        now: Long,
    ): List<Pair<String, Array<out Any?>>> {
        require(title.isNotBlank() && tracks.isNotEmpty())
        val unique = tracks.distinctBy { it.id }
        val artwork = cover?.takeIf { it.isNotBlank() }
            ?: unique.firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) }.orEmpty()
        val statements = mutableListOf<Pair<String, Array<out Any?>>>()
        statements += """INSERT INTO downloaded_playlists
            (id,title,artist,artworkUrl,trackCount,isUserCreated,localCoverPath,permalinkUrl,isAlbum,addedAt,isDownloaded)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)""" to arrayOf<Any?>(id, title, artist, artwork, unique.size, true, null, null, false, now, 0)
        unique.forEachIndexed { index, track ->
            statements += """INSERT OR IGNORE INTO downloaded_tracks
                (id,title,artist,artworkUrl,duration,localAudioPath,localArtworkPath,downloadedAt,source,permalinkUrl)
                VALUES(?,?,?,?,?,?,?,?,?,?)""" to arrayOf<Any?>(
                track.id, track.title.orEmpty(), track.displayArtist.ifBlank { track.user?.username.orEmpty() },
                track.artworkUrl.orEmpty(), track.durationMs ?: 0L, "", "", now,
                track.source?.takeUnless { it == "soundcloud" }, track.permalinkUrl,
            )
            statements += "INSERT INTO playlist_track_cross_ref(playlistId,trackId,addedAt) VALUES(?,?,?)" to
                arrayOf<Any?>(id, track.id, now + index)
        }
        return statements
    }
}
