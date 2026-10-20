package com.alananasss.kittytune.data.lossless

import com.alananasss.kittytune.audio.providers.qobuz.QobuzAudioProvider
import com.alananasss.kittytune.data.DownloadManager
import com.alananasss.kittytune.data.ResolvedStream
import com.alananasss.kittytune.data.download.LosslessDownloadPolicy
import com.alananasss.kittytune.data.local.PlayerPreferences
import com.alananasss.kittytune.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

/** Resolves only genuine FLAC candidates; the normal provider fallback remains unchanged. */
internal object LosslessAudioRepository {
    suspend fun resolve(track: Track, allowLocal: Boolean = true, verifyStream: Boolean = true): ResolvedStream? {
        if (allowLocal) {
            val local = withContext(Dispatchers.IO) {
                val path = DownloadManager.getLocalTrack(track.id)?.localAudioPath
                path?.takeIf { it.isNotBlank() && LosslessDownloadPolicy.acceptsFile(File(it)) }
            }
            if (local != null) return ResolvedStream(local, mimeType = "audio/flac", source = "local")
        }
        val stream = runInterruptible(Dispatchers.IO) {
            val prefs = PlayerPreferences()
            val resolved = QobuzAudioProvider.resolve(QobuzAudioProvider.Query(
                mediaId = track.permalink?.takeIf { it.isNotBlank() } ?: track.id.toString(),
                title = track.title.orEmpty(),
                artists = listOf(track.displayArtist),
                album = track.publisherMetadata?.albumTitle,
                isrc = track.publisherMetadata?.isrc,
                durationMs = track.effectiveFullDurationMs,
                countryCode = prefs.getQobuzCountry(),
                qualityCode = 27,
                customInstances = prefs.getQobuzCustomInstances(),
                losslessOnly = true,
            ))
            if (!LosslessDownloadPolicy.acceptsStream(resolved.mimeType, resolved.mediaUri)) return@runInterruptible null
            ResolvedStream(resolved.mediaUri, mimeType = resolved.mimeType, source = "qobuz")
        } ?: return null
        if (verifyStream && !FlacStreamProbe().verify(stream.url)) return null
        return stream
    }
}
