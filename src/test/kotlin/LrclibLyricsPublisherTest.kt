package com.alananasss.kittytune.data.lyrics.publishing

import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LrclibLyricsPublisherTest {
    private val prefix = "A".repeat(32)
    private val draft = LyricsPublishDraft(" Song ", " Artist ", " Album ", 95.492, "First\nSecond", "[00:01.50]First\n[00:03.25]Second")

    @Test
    fun `requests one fresh challenge and publishes the legacy lyrics contract with a valid token`() = runBlocking<Unit> {
        val requests = ConcurrentLinkedQueue<Request>()
        fakeClient { chain ->
            requests.add(chain.request())
            if (chain.request().url.encodedPath.endsWith("request-challenge")) reply(chain, 200, challenge()) else reply(chain, 201, "")
        }.useClient { client ->
            assertEquals(LyricsPublishResult.Published, LrclibLyricsPublisher(client).publish(draft))
        }
        val sent = requests.toList()
        assertEquals(listOf("/api/request-challenge", "/api/publish"), sent.map { it.url.encodedPath })
        assertTrue(sent.all { it.method == "POST" && it.url.host == "lrclib.net" })
        assertNull(sent[0].header("X-Publish-Token"))
        assertEquals("$prefix:0", sent[1].header("X-Publish-Token"))
        assertEquals("KittyTuneDesktop", sent[1].header("Lrclib-Client"))
        val buffer = Buffer()
        sent[1].body!!.writeTo(buffer)
        val json = JsonParser.parseString(buffer.readUtf8()).asJsonObject
        assertEquals("Song", json["trackName"].asString)
        assertEquals("Artist", json["artistName"].asString)
        assertEquals("Album", json["albumName"].asString)
        assertEquals(95.492, json["duration"].asDouble)
        assertEquals(draft.plainLyrics, json["plainLyrics"].asString)
        assertEquals(draft.syncedLyrics, json["syncedLyrics"].asString)
        assertFalse(json.has("lyricsfile"))
    }

    @Test
    fun `server publication rejection is typed and is never automatically retried`() = runBlocking<Unit> {
        val requests = ConcurrentLinkedQueue<Request>()
        fakeClient { chain ->
            requests.add(chain.request())
            if (requests.size == 1) reply(chain, 200, challenge())
            else reply(chain, 400, """{"name":"IncorrectPublishTokenError","message":"do not display raw response"}""")
        }.useClient { client ->
            assertEquals(
                LyricsPublishResult.HttpRejected(LyricsPublishResult.Stage.PUBLISH, 400, "IncorrectPublishTokenError", null),
                LrclibLyricsPublisher(client).publish(draft),
            )
        }
        assertEquals(2, requests.size)
    }

    @Test
    fun `challenge throttling carries retry delay and never sends lyrics`() = runBlocking<Unit> {
        val requests = ConcurrentLinkedQueue<Request>()
        fakeClient { chain ->
            requests.add(chain.request())
            reply(chain, 429, """{"name":"RateLimited"}""").newBuilder().header("Retry-After", "8").build()
        }.useClient { client ->
            assertEquals(
                LyricsPublishResult.HttpRejected(LyricsPublishResult.Stage.CHALLENGE, 429, "RateLimited", 8L),
                LrclibLyricsPublisher(client).publish(draft),
            )
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun `invalid draft is rejected before any HTTP request`() = runBlocking<Unit> {
        fakeClient { error("Invalid draft must not reach HTTP") }.useClient { client ->
            val publisher = LrclibLyricsPublisher(client)
            assertIs<LyricsPublishResult.InvalidDraft>(publisher.publish(draft.copy(durationSeconds = Double.NaN)))
            assertIs<LyricsPublishResult.InvalidDraft>(publisher.publish(draft.copy(trackName = " ")))
            assertIs<LyricsPublishResult.InvalidDraft>(publisher.publish(draft.copy(plainLyrics = null, syncedLyrics = null)))
            assertIs<LyricsPublishResult.InvalidDraft>(publisher.publish(draft.copy(syncedLyrics = "x".repeat(131_073))))
        }
    }

    @Test
    fun `malformed challenge and oversized response never reach publication`() = runBlocking<Unit> {
        val requests = ConcurrentLinkedQueue<Request>()
        fakeClient { chain -> requests.add(chain.request()); reply(chain, 200, """{"prefix":"bad:prefix","target":"${"F".repeat(64)}"}""") }
            .useClient { client -> assertEquals(LyricsPublishResult.InvalidChallenge, LrclibLyricsPublisher(client).publish(draft)) }
        assertEquals(1, requests.size)
        fakeClient { chain -> reply(chain, 200, "x".repeat(16_385)) }
            .useClient { client -> assertEquals(LyricsPublishResult.ResponseTooLarge, LrclibLyricsPublisher(client).publish(draft)) }
    }

    @Test
    fun `work exhaustion does not send a publish request`() = runBlocking<Unit> {
        val requests = ConcurrentLinkedQueue<Request>()
        fakeClient { chain -> requests.add(chain.request()); reply(chain, 200, challenge("0".repeat(64))) }
            .useClient { client ->
                assertEquals(LyricsPublishResult.WorkLimitReached, LrclibLyricsPublisher(client, PublishWorkBudget(maxAttempts = 1)).publish(draft))
            }
        assertEquals(1, requests.size)
    }

    @Test
    fun `proof hashes prefix plus nonce without a colon and accepts exact threshold equality`() = runBlocking<Unit> {
        val hash = MessageDigest.getInstance("SHA-256").digest((prefix + "0").toByteArray())
        val target = hash.joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("0", LrclibProofOfWork.solve(LrclibChallenge(prefix, target), PublishWorkBudget(maxAttempts = 1)))
        val colonHash = MessageDigest.getInstance("SHA-256").digest((prefix + ":0").toByteArray())
        assertFalse(hash.contentEquals(colonHash))
    }

    @Test
    fun `proof comparison is unsigned big endian and accepts equality`() {
        val lower = ByteArray(32).apply { this[0] = 0x7F }
        val higher = ByteArray(32).apply { this[0] = 0x80.toByte() }
        assertTrue(LrclibProofOfWork.atOrBelow(lower, higher))
        assertFalse(LrclibProofOfWork.atOrBelow(higher, lower))
        assertTrue(LrclibProofOfWork.atOrBelow(higher, higher))
    }

    @Test
    fun `proof solving cooperates with coroutine cancellation`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val job = launch {
            withContext(kotlinx.coroutines.Dispatchers.Default) {
                started.complete(Unit)
                LrclibProofOfWork.solve(LrclibChallenge(prefix, "0".repeat(64)), PublishWorkBudget(100_000_000L, 60_000L))
            }
        }
        started.await()
        delay(10L)
        withTimeout(3_000L) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test
    fun `cancelling a pending request cancels the actual OkHttp call`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val finished = CountDownLatch(1)
        val activeCall = AtomicReference<okhttp3.Call>()
        fakeClient { chain ->
            activeCall.set(chain.call())
            entered.complete(Unit)
            try {
                while (!chain.call().isCanceled()) Thread.sleep(1L)
                throw IOException("Cancelled fake call")
            } finally { finished.countDown() }
        }.useClient { client ->
            val publication = async { LrclibLyricsPublisher(client).publish(draft) }
            withTimeout(3_000L) { entered.await() }
            withTimeout(3_000L) { publication.cancelAndJoin() }
            assertTrue(activeCall.get().isCanceled())
            assertTrue(finished.await(3L, TimeUnit.SECONDS))
        }
    }

    private fun challenge(target: String = "F".repeat(64)) = """{"prefix":"$prefix","target":"$target"}"""
    private fun fakeClient(block: (Interceptor.Chain) -> Response): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { block(it) }).build()
    private fun reply(chain: Interceptor.Chain, code: Int, body: String) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fake response")
        .body(body.toResponseBody("application/json".toMediaType())).build()
    private suspend fun <T> OkHttpClient.useClient(block: suspend (OkHttpClient) -> T): T = try {
        block(this)
    } finally {
        dispatcher.executorService.shutdownNow()
        connectionPool.evictAll()
    }
}
