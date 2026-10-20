package com.alananasss.kittytune.data.lossless

import com.alananasss.kittytune.core.AppDirs
import com.alananasss.kittytune.data.DownloadManager
import com.alananasss.kittytune.data.download.LosslessDownloadPolicy
import com.alananasss.kittytune.data.local.AppDatabase
import com.alananasss.kittytune.data.local.LocalTrack
import com.alananasss.kittytune.data.local.PlayerPreferences
import com.alananasss.kittytune.domain.Track
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.nio.ShortBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LosslessDownloadIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `strict download upgrades an existing MP3 to real FLAC without deleting the old file`() = runBlocking {
        exerciseDownload(corrupt = false)
    }

    @Test fun `a lying FLAC response does not replace the existing download or publish a false FLAC file`() = runBlocking {
        exerciseDownload(corrupt = true)
    }

    private suspend fun exerciseDownload(corrupt: Boolean) {
        val isolatedRoot = System.getenv("KITTYTUNE_ISOLATED_TEST_ROOT")?.let { File(it).canonicalFile }
        assumeTrue("Writing integration requires an explicitly isolated app profile",
            isolatedRoot != null && AppDirs.dataDir.canonicalFile.toPath().startsWith(isolatedRoot.toPath()))
        AppDatabase.init()
        val prefs = PlayerPreferences()
        val oldLocation = prefs.getDownloadLocation()
        val oldInstances = prefs.getQobuzCustomInstances()
        val output = temporary.newFolder("downloads")
        val oldMp3 = File(output, "original.mp3").apply { writeBytes("ID3own-fixture".toByteArray()) }
        val originalBytes = oldMp3.readBytes()
        val payload = if (corrupt) originalBytes else makeFlac()
        val id = System.nanoTime()
        val requests = AtomicInteger()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        server.executor = executor
        server.createContext("/api/download-music") { exchange ->
            requests.incrementAndGet()
            val body = """{"success":true,"data":{"url":"$base/audio","mime_type":"audio/flac","format_id":6}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/audio") { exchange ->
            exchange.responseHeaders.set("Content-Type", "audio/flac")
            if (exchange.requestMethod == "HEAD") {
                exchange.responseHeaders.set("Content-Length", payload.size.toString())
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
        }
        server.start()
        try {
            prefs.saveDownloadLocation(output.absolutePath)
            prefs.setQobuzCustomInstances(base)
            AppDatabase.downloadDao.insertTrack(LocalTrack(id, "Own fixture", "Fixture", "", 100,
                oldMp3.absolutePath, "", source = "qobuz"))
            val track = Track(id, "Own fixture", null, 100, null, source = "qobuz",
                permalink = "qobuz:track:$id", requireLossless = true)
            DownloadManager.downloadTrack(track)
            withTimeout(20_000) {
                while (requests.get() == 0 || DownloadManager.isTrackDownloading(id)) delay(10)
            }
            val stored = requireNotNull(DownloadManager.getLocalTrack(id))
            assertContentEquals(originalBytes, oldMp3.readBytes())
            if (corrupt) {
                assertEquals(oldMp3.absolutePath, stored.localAudioPath)
                assertTrue(output.listFiles().orEmpty().none { it.extension == "flac" })
            } else {
                val downloaded = File(stored.localAudioPath)
                assertEquals("flac", downloaded.extension)
                assertTrue(LosslessDownloadPolicy.acceptsFile(downloaded))
                assertContentEquals(payload, downloaded.readBytes())
            }
        } finally {
            DownloadManager.cancelDownload(id)
            withTimeout(5_000) { while (DownloadManager.isTrackDownloading(id)) delay(10) }
            AppDatabase.downloadDao.deleteTrack(id)
            prefs.saveDownloadLocation(oldLocation)
            prefs.setQobuzCustomInstances(oldInstances)
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun makeFlac(): ByteArray {
        val file = temporary.newFile("own-silence.flac")
        val recorder = FFmpegFrameRecorder(file, 2).apply {
            format = "flac"
            audioCodec = avcodec.AV_CODEC_ID_FLAC
            sampleRate = 44_100
        }
        try {
            recorder.start()
            recorder.recordSamples(44_100, 2, ShortBuffer.wrap(ShortArray(8_820)))
            recorder.stop()
        } finally { recorder.release() }
        return file.readBytes()
    }
}
