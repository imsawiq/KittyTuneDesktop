package com.alananasss.kittytune.data.lyrics

import com.alananasss.kittytune.data.network.LrcLibApiService
import com.alananasss.kittytune.data.network.LrcLibResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

/** Structured and free-text search can each provide useful lyrics independently. */
internal object LrcLibLyricsRequests {
    suspend fun fetch(
        api: LrcLibApiService,
        title: String,
        artist: String,
        query: String,
        onResults: suspend (List<LrcLibResponse>) -> Unit = {},
    ): List<LrcLibResponse> = supervisorScope {
        suspend fun request(fetch: suspend () -> List<LrcLibResponse>): List<LrcLibResponse> {
            val results = try {
                fetch()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            onResults(results)
            return results
        }
        val structured = async {
            request {
                if (title.isBlank() || artist.isBlank()) emptyList()
                else api.searchLyricsStructured(title, artist)
            }
        }
        val freeText = async { request { api.searchLyrics(query) } }
        (structured.await() + freeText.await()).distinctBy { it.id }
    }
}
