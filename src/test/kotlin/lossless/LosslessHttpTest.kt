package com.alananasss.kittytune.data.lossless

import com.alananasss.kittytune.audio.providers.qobuz.QobuzAudioProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LosslessHttpTest {
    private lateinit var server: HttpServer
    private val executor = Executors.newCachedThreadPool()
    private val release = CountDownLatch(1)
    private val received = CountDownLatch(1)
    private val range = AtomicReference<String?>()
    private val qualities = Collections.synchronizedList(mutableListOf<Int>())
    private val allowFlac = AtomicBoolean(true)
    private val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
    private val base: String get() = "http://127.0.0.1:${server.address.port}"

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/audio") { exchange ->
            range.set(exchange.requestHeaders.getFirst("Range"))
            if (exchange.requestURI.rawQuery == "wait") {
                received.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            val bytes = if (exchange.requestURI.rawQuery == "mp3") "ID3!".toByteArray() else "fLaC".toByteArray()
            exchange.responseHeaders.set("Content-Type", "audio/flac")
            if (exchange.requestMethod == "HEAD") {
                exchange.responseHeaders.set("Content-Length", "1000000")
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.createContext("/api/download-music") { exchange ->
            val quality = exchange.requestURI.rawQuery.split('&').first { it.startsWith("quality=") }.substringAfter('=').toInt()
            qualities += quality
            val flac = quality == 6 && allowFlac.get()
            val json = """{"success":true,"data":{"url":"$base/audio?${if (flac) "flac" else "mp3"}","mime_type":"${if (flac) "audio/flac" else "audio/mpeg"}","format_id":${if (flac) 6 else 5}}}""".toByteArray()
            exchange.sendResponseHeaders(200, json.size.toLong())
            exchange.responseBody.use { it.write(json) }
        }
        server.start()
    }

    @After fun stop() {
        release.countDown()
        server.stop(0)
        executor.shutdownNow()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
    }

    @Test fun `signature probe accepts range ignored FLAC and rejects a lying MIME`() = runBlocking {
        val probe = FlacStreamProbe(client)
        assertTrue(probe.verify("$base/audio?flac"))
        assertEquals("bytes=0-3", range.get())
        assertFalse(probe.verify("$base/audio?mp3"))
        assertFalse(probe.verify("$base/missing"))
        assertFalse(probe.verify("not a valid HTTP URL"))
    }

    @Test fun `cancelling a signature probe cancels its real HTTP call`() = runBlocking {
        val call = AtomicReference<Call?>()
        val probe = FlacStreamProbe(Call.Factory { request -> client.newCall(request).also(call::set) })
        val job = async { probe.verify("$base/audio?wait") }
        assertTrue(withContext(Dispatchers.IO) { received.await(3, TimeUnit.SECONDS) })
        job.cancelAndJoin()
        assertTrue(call.get()?.isCanceled() == true)
    }

    @Test fun `strict provider skips lossy responses and keeps separate stream cache identity`() {
        val query = QobuzAudioProvider.Query(
            mediaId = "qobuz:track:${System.nanoTime()}", title = "Own fixture", artists = listOf("Fixture"),
            album = null, isrc = null, durationMs = 180_000, customInstances = base,
        )
        assertEquals("audio/mpeg", QobuzAudioProvider.resolve(query).mimeType)
        val strict = QobuzAudioProvider.resolve(query.copy(losslessOnly = true))
        assertEquals("audio/flac", strict.mimeType)
        assertEquals(listOf(27, 27, 7, 6), qualities.toList())
        assertTrue(FlacStreamProbe(client).let { runBlocking { it.verify(strict.mediaUri) } })
    }

    @Test fun `strict provider reports unavailable instead of requesting MP3 fallback`() {
        allowFlac.set(false)
        val query = QobuzAudioProvider.Query(
            mediaId = "qobuz:track:${System.nanoTime()}", title = "Unavailable fixture", artists = listOf("Fixture"),
            album = null, isrc = null, durationMs = 180_000, customInstances = base, losslessOnly = true,
        )
        assertFailsWith<QobuzAudioProvider.QobuzResolutionException> { QobuzAudioProvider.resolve(query) }
        assertEquals(listOf(27, 7, 6), qualities.toList())
    }
}
