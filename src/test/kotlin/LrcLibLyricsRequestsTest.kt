package com.alananasss.kittytune.data.lyrics

import com.alananasss.kittytune.data.network.LrcLibApiService
import com.alananasss.kittytune.data.network.LrcLibResponse
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LrcLibLyricsRequestsTest {
    @Test fun `fast structured result is published and retained when free text exceeds source deadline`() = runBlocking {
        val releaseFreeText = CompletableDeferred<Unit>()
        val published = CompletableDeferred<List<LrcLibResponse>>()
        val expected = response(1, "structured song")
        val api = FakeApi(structured = { listOf(expected) }, freeText = {
            releaseFreeText.await()
            emptyList()
        })
        val lookup = async {
            ParallelLyricsLookup.resolve(
                sources = listOf<suspend (suspend (List<LrcLibResponse>) -> Unit) -> List<LrcLibResponse>?>(
                    { publish ->
                        LrcLibLyricsRequests.fetch(api, "song", "artist", "song artist") { results ->
                            if (results.isNotEmpty()) publish(results)
                        }
                    },
                ),
                timeoutMs = 500,
                ranking = compareBy<IndexedValue<List<LrcLibResponse>>> { it.value.size },
            ) { published.complete(it) }
        }
        try {
            assertEquals(listOf(expected), withTimeout(2_000) { published.await() })
            assertFalse(releaseFreeText.isCompleted)
            assertEquals(listOf(expected), withTimeout(2_000) { lookup.await() })
        } finally {
            lookup.cancelAndJoin()
        }
    }

    @Test fun `free text publishes while structured lookup is blocked and final merge preserves identities`() = runBlocking {
        val releaseStructured = CompletableDeferred<Unit>()
        val published = CompletableDeferred<List<LrcLibResponse>>()
        val structured = response(1, "structured song")
        val freeText = response(2, "free text song")
        val api = FakeApi(structured = {
            releaseStructured.await()
            listOf(structured)
        }, freeText = { listOf(freeText) })
        val fetch = async {
            LrcLibLyricsRequests.fetch(api, "song", "artist", "song artist") {
                if (it.isNotEmpty()) published.complete(it)
            }
        }
        try {
            assertEquals(listOf(freeText), withTimeout(2_000) { published.await() })
            assertFalse(fetch.isCompleted)
            releaseStructured.complete(Unit)
            assertEquals(listOf(structured, freeText), withTimeout(2_000) { fetch.await() })
        } finally {
            fetch.cancelAndJoin()
        }
    }

    @Test fun `unavailable endpoint does not erase independent successful search`() = runBlocking {
        val expected = response(1, "song")
        val api = FakeApi(structured = { throw IOException("Unavailable") }, freeText = { listOf(expected) })
        assertEquals(listOf(expected), LrcLibLyricsRequests.fetch(api, "song", "artist", "song artist"))
    }

    private fun response(id: Long, name: String) = LrcLibResponse(
        id = id, name = name, trackName = name, artistName = "artist", albumName = null,
        duration = 4.0, plainLyrics = "$name plain", syncedLyrics = "[00:01.00]$name", lyricsfile = null,
    )

    private class FakeApi(
        val structured: suspend () -> List<LrcLibResponse>,
        val freeText: suspend () -> List<LrcLibResponse>,
    ) : LrcLibApiService {
        override suspend fun searchLyricsStructured(trackName: String, artistName: String) = structured()
        override suspend fun searchLyrics(query: String) = freeText()
        override suspend fun getLyrics(trackName: String, artistName: String, duration: Long): LrcLibResponse = error("Unused")
        override suspend fun getLyricsById(id: Long): LrcLibResponse = error("Unused")
    }
}
