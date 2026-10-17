package com.alananasss.kittytune.data.lyrics

import com.alananasss.kittytune.data.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class MusixmatchLyricsRequestsTest {
    @Test fun `plain rich synced and performer requests all begin before any response`(): Unit = runBlocking {
        val count = AtomicInteger()
        val allStarted = CompletableDeferred<Unit>()
        val api = FakeApi(before = {
            if (count.incrementAndGet() == 4) allStarted.complete(Unit)
            allStarted.await()
        })
        val responses = withTimeout(2_000) { MusixmatchLyricsRequests.fetch(api, 42, "token") }
        assertEquals(4, count.get())
        assertEquals("plain", responses.plain?.message?.body?.lyrics?.lyricsBody)
        assertNotNull(responses.subtitle)
        assertNotNull(responses.richSync)
        assertNotNull(responses.track)
    }

    @Test fun `one unavailable endpoint leaves successful lyrics intact`(): Unit = runBlocking {
        val responses = MusixmatchLyricsRequests.fetch(FakeApi(failPlain = true), 42, "token")
        assertNull(responses.plain)
        assertNotNull(responses.subtitle)
        assertNotNull(responses.richSync)
    }

    @Test fun `cancellation is propagated instead of becoming unavailable lyrics`(): Unit = runBlocking {
        assertFailsWith<CancellationException> {
            MusixmatchLyricsRequests.fetch(FakeApi(cancelPlain = true), 42, "token")
        }
        Unit
    }

    @Test fun `token renewal is detected on any lyrics endpoint`(): Unit = runBlocking {
        val responses = MusixmatchLyricsRequests.fetch(FakeApi(renewRichSync = true), 42, "token")
        assertTrue(responses.needsTokenRenewal)
    }

    private class FakeApi(
        val before: suspend () -> Unit = {},
        val failPlain: Boolean = false,
        val cancelPlain: Boolean = false,
        val renewRichSync: Boolean = false,
    ) : MusixmatchApiService {
        private fun <T> success(body: T) = MxmResponse(MxmMessage(MxmHeader(200), body))
        override suspend fun getSubtitle(trackId: Long, subtitleFormat: String, token: String): MxmResponse<MxmSubtitleBody> {
            before()
            return success(MxmSubtitleBody(MxmSubtitleObj("[]")))
        }
        override suspend fun getRichSync(trackId: Long, token: String): MxmResponse<MxmRichSyncBody> {
            before()
            return if (renewRichSync) MxmResponse(MxmMessage(MxmHeader(401, "renew"), null))
            else success(MxmRichSyncBody(MxmRichSyncObj("[]")))
        }
        override suspend fun getLyrics(trackId: Long, token: String): MxmResponse<MxmLyricsBody> {
            before()
            if (cancelPlain) throw CancellationException("Track changed")
            if (failPlain) throw IOException("Unavailable")
            return success(MxmLyricsBody(MxmLyricsObj("plain")))
        }
        override suspend fun getTrack(trackId: Long, part: String, token: String): MxmResponse<MxmTrackBody> {
            before()
            return success(MxmTrackBody(null))
        }
        override suspend fun getToken(advId: String, root: String, sideloaded: String, buildNumber: String,
            guid: String, lang: String, model: String, timestamp: String): MxmResponse<MxmTokenBody> = error("Unused")
        override suspend fun searchTrack(query: String, sort: String, limit: Int, token: String): MxmResponse<MxmTrackListBody> = error("Unused")
        override suspend fun getTranslations(trackId: Long, lang: String, fieldsSet: String, token: String): MxmResponse<MxmTranslationListBody> = error("Unused")
    }
}
