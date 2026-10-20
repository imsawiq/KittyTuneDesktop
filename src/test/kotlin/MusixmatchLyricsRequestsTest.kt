package com.alananasss.kittytune.data.lyrics

import com.alananasss.kittytune.data.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.IOException
import com.alananasss.kittytune.ui.player.lyrics.LyricLine
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

    @Test fun `subtitle and word lyrics publish before optional performer metadata and survive deadline`(): Unit = runBlocking {
        val releaseWords = CompletableDeferred<Unit>()
        val releaseMetadata = CompletableDeferred<Unit>()
        val firstLines = CompletableDeferred<List<LyricLine>>()
        val firstWords = CompletableDeferred<List<LyricLine>>()
        val api = FakeApi(beforePart = { part ->
            when (part) {
                "rich" -> releaseWords.await()
                "track" -> releaseMetadata.await()
            }
        }, subtitleJson = """[{"time":{"total":1.0},"text":"same song"}]""",
            richJson = """[{"ts":1.0,"te":3.0,"x":"same song","l":[{"c":"same ","o":0.0},{"c":"song","o":1.0}]}]""")
        val lookup = async {
            ParallelLyricsLookup.resolve(
                sources = listOf<suspend (suspend (List<LyricLine>) -> Unit) -> List<LyricLine>?>(
                    { publish ->
                        val responses = MusixmatchLyricsRequests.fetch(api, 42, "token") { snapshot ->
                            val lines = MusixmatchLyricsPayload.decode(snapshot, 4_000).first
                            if (lines.isNotEmpty()) publish(lines)
                        }
                        MusixmatchLyricsPayload.decode(responses, 4_000).first
                    },
                ),
                timeoutMs = 1_000,
                ranking = compareBy<IndexedValue<List<LyricLine>>> {
                    if (it.value.any { line -> line.words.isNotEmpty() }) 3 else 2
                },
            ) { lines ->
                firstLines.complete(lines)
                if (lines.any { it.words.isNotEmpty() }) firstWords.complete(lines)
            }
        }
        try {
            val lines = withTimeout(2_000) { firstLines.await() }
            assertEquals("same song", lines.single().text)
            assertEquals(4_000L, lines.single().endTime)
            assertFalse(lookup.isCompleted)
            releaseWords.complete(Unit)
            val words = withTimeout(2_000) { firstWords.await() }
            assertEquals(listOf(1_000L, 2_000L), words.single().words.map { it.startTime })
            assertEquals(3_000L, words.single().words.last().endTime)
            assertFalse(releaseMetadata.isCompleted)
            assertEquals(words, withTimeout(2_000) { lookup.await() })
        } finally {
            lookup.cancelAndJoin()
        }
    }

    @Test fun `plain response can be decoded before other endpoints arrive`() = runBlocking {
        val releaseOthers = CompletableDeferred<Unit>()
        val published = CompletableDeferred<String>()
        val api = FakeApi(beforePart = { if (it != "plain") releaseOthers.await() })
        val fetch = async {
            MusixmatchLyricsRequests.fetch(api, 42, "token") { snapshot ->
                MusixmatchLyricsPayload.decode(snapshot, 4_000).second?.let { published.complete(it) }
            }
        }
        try {
            assertEquals("plain", withTimeout(2_000) { published.await() })
            assertFalse(fetch.isCompleted)
        } finally {
            fetch.cancelAndJoin()
        }
    }

    private class FakeApi(
        val before: suspend () -> Unit = {},
        val beforePart: suspend (String) -> Unit = {},
        val subtitleJson: String = "[]",
        val richJson: String = "[]",
        val failPlain: Boolean = false,
        val cancelPlain: Boolean = false,
        val renewRichSync: Boolean = false,
    ) : MusixmatchApiService {
        private fun <T> success(body: T) = MxmResponse(MxmMessage(MxmHeader(200), body))
        override suspend fun getSubtitle(trackId: Long, subtitleFormat: String, token: String): MxmResponse<MxmSubtitleBody> {
            before()
            check(trackId == 42L && token == "token")
            beforePart("subtitle")
            return success(MxmSubtitleBody(MxmSubtitleObj(subtitleJson)))
        }
        override suspend fun getRichSync(trackId: Long, token: String): MxmResponse<MxmRichSyncBody> {
            before()
            check(trackId == 42L && token == "token")
            beforePart("rich")
            return if (renewRichSync) MxmResponse(MxmMessage(MxmHeader(401, "renew"), null))
            else success(MxmRichSyncBody(MxmRichSyncObj(richJson)))
        }
        override suspend fun getLyrics(trackId: Long, token: String): MxmResponse<MxmLyricsBody> {
            before()
            check(trackId == 42L && token == "token")
            beforePart("plain")
            if (cancelPlain) throw CancellationException("Track changed")
            if (failPlain) throw IOException("Unavailable")
            return success(MxmLyricsBody(MxmLyricsObj("plain")))
        }
        override suspend fun getTrack(trackId: Long, part: String, token: String): MxmResponse<MxmTrackBody> {
            before()
            check(trackId == 42L && token == "token")
            beforePart("track")
            return success(MxmTrackBody(null))
        }
        override suspend fun getToken(advId: String, root: String, sideloaded: String, buildNumber: String,
            guid: String, lang: String, model: String, timestamp: String): MxmResponse<MxmTokenBody> = error("Unused")
        override suspend fun searchTrack(query: String, sort: String, limit: Int, token: String): MxmResponse<MxmTrackListBody> = error("Unused")
        override suspend fun getTranslations(trackId: Long, lang: String, fieldsSet: String, token: String): MxmResponse<MxmTranslationListBody> = error("Unused")
    }
}
