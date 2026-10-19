package com.alananasss.kittytune.audio.ai.karaoke

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HTDemucsModelDownloadTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var server: HttpServer
    private val executor = Executors.newCachedThreadPool()
    private val release = CountDownLatch(1)
    private val payload = "verified model bytes".toByteArray()
    private val activeCall = AtomicReference<Call?>(null)

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.start()
    }

    @After fun stopServer() {
        release.countDown()
        server.stop(0)
        executor.shutdownNow()
    }

    @Test fun `verified primary is accepted without requesting mirror`() = runBlocking {
        val mirrorRequests = AtomicInteger()
        server.createContext("/primary") { it.respond(payload) }
        server.createContext("/mirror") { mirrorRequests.incrementAndGet(); it.respond(payload) }
        val temporary = directory.newFile()
        downloader().download(temporary, activeCall) {}
        assertContentEquals(payload, temporary.readBytes())
        assertEquals(0, mirrorRequests.get())
        assertNull(activeCall.get())
    }

    @Test fun `HTTP error falls back to verified mirror`() = runBlocking {
        server.createContext("/primary") { it.respond(byteArrayOf(), 503) }
        assertMirrorRecovers()
    }

    @Test fun `truncated primary falls back to verified mirror`() = runBlocking {
        server.createContext("/primary") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(payload.copyOf(3)) }
            exchange.close()
        }
        assertMirrorRecovers()
    }

    @Test fun `corrupt primary falls back to verified mirror`() = runBlocking {
        server.createContext("/primary") { it.respond(ByteArray(payload.size)) }
        assertMirrorRecovers()
    }

    @Test fun `all invalid sources remove partial file and preserve installed model`() = runBlocking {
        server.createContext("/primary") { it.respond(ByteArray(payload.size)) }
        server.createContext("/mirror") { it.respond(byteArrayOf(1)) }
        val installed = directory.newFile("htdemucs.safetensors").apply { writeBytes(payload) }
        val temporary = directory.newFile("htdemucs.part")
        assertFailsWith<IllegalStateException> { downloader().download(temporary, activeCall) {} }
        assertContentEquals(payload, installed.readBytes())
        assertFalse(temporary.exists())
        assertNull(activeCall.get())
    }

    @Test fun `cancellation closes stalled response without trying mirror`() = runBlocking {
        val receiving = CompletableDeferred<Unit>()
        server.createContext("/primary") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.write(payload, 0, 1)
            exchange.responseBody.flush()
            release.await(10, TimeUnit.SECONDS)
            exchange.close()
        }
        val mirrorRequests = AtomicInteger()
        server.createContext("/mirror") { mirrorRequests.incrementAndGet(); it.respond(payload) }
        val temporary = directory.newFile()
        val download = launch(Dispatchers.IO) {
            downloader().download(temporary, activeCall) { if (it > 0) receiving.complete(Unit) }
        }
        withTimeout(5_000) { receiving.await() }
        withTimeout(2_000) { download.cancelAndJoin() }
        assertTrue(download.isCancelled)
        assertFalse(temporary.exists())
        assertNull(activeCall.get())
        assertEquals(0, mirrorRequests.get())
    }

    @Test fun `cancellation closes request waiting for response headers`() = runBlocking {
        val requested = CompletableDeferred<Unit>()
        server.createContext("/primary") { exchange ->
            requested.complete(Unit)
            release.await(10, TimeUnit.SECONDS)
            exchange.close()
        }
        val temporary = directory.newFile()
        val download = launch(Dispatchers.IO) { downloader().download(temporary, activeCall) {} }
        withTimeout(5_000) { requested.await() }
        withTimeout(2_000) { download.cancelAndJoin() }
        assertTrue(download.isCancelled)
        assertFalse(temporary.exists())
        assertNull(activeCall.get())
    }

    private suspend fun assertMirrorRecovers() {
        val mirrorRequests = AtomicInteger()
        server.createContext("/mirror") { mirrorRequests.incrementAndGet(); it.respond(payload) }
        val temporary = directory.newFile()
        downloader().download(temporary, activeCall) {}
        assertContentEquals(payload, temporary.readBytes())
        assertEquals(1, mirrorRequests.get())
        assertNull(activeCall.get())
    }

    private fun downloader() = HTDemucsModelDownload(
        OkHttpClient(),
        listOf("http://127.0.0.1:${server.address.port}/primary", "http://127.0.0.1:${server.address.port}/mirror"),
        payload.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 0xff) },
    )

    private fun HttpExchange.respond(bytes: ByteArray, status: Int = 200) {
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }
}
