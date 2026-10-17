package com.alananasss.kittytune.data.lyrics

import com.alananasss.kittytune.data.network.MusixmatchApiService
import com.alananasss.kittytune.data.network.MxmLyricsBody
import com.alananasss.kittytune.data.network.MxmResponse
import com.alananasss.kittytune.data.network.MxmRichSyncBody
import com.alananasss.kittytune.data.network.MxmSubtitleBody
import com.alananasss.kittytune.data.network.MxmTrackBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/** These endpoints describe the same track and do not depend on each other's response. */
internal object MusixmatchLyricsRequests {
    data class Responses(
        val subtitle: MxmResponse<MxmSubtitleBody>?,
        val richSync: MxmResponse<MxmRichSyncBody>?,
        val plain: MxmResponse<MxmLyricsBody>?,
        val track: MxmResponse<MxmTrackBody>?,
    ) {
        val needsTokenRenewal: Boolean get() = listOf(subtitle?.message?.header, richSync?.message?.header,
            plain?.message?.header, track?.message?.header).any { it?.statusCode == 401 && it.hint == "renew" }
    }

    suspend fun fetch(api: MusixmatchApiService, trackId: Long, token: String): Responses = supervisorScope {
        val subtitle = async { optional { api.getSubtitle(trackId = trackId, token = token) } }
        val richSync = async { optional { api.getRichSync(trackId, token) } }
        val plain = async { optional { api.getLyrics(trackId, token) } }
        val track = async { optional { api.getTrack(trackId, token = token) } }
        Responses(subtitle.await(), richSync.await(), plain.await(), track.await())
    }

    private suspend fun <T> optional(request: suspend () -> T): T? = withTimeoutOrNull(7_000) {
        try { request() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}
