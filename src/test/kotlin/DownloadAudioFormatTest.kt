package com.alananasss.kittytune.data.download

import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadAudioFormatTest {
    @Test fun `opaque signed FLAC URLs retain the provider format`() {
        assertEquals(DownloadAudioFormat.FLAC, DownloadAudioFormat.forStream("audio/flac", "https://example.test/stream?signature=x", false))
        assertEquals(DownloadAudioFormat.FLAC, DownloadAudioFormat.forStream("AUDIO/X-FLAC; codecs=flac", "https://example.test/stream", false))
    }

    @Test fun `YouTube WebM is not mislabeled as M4A`() {
        assertEquals(DownloadAudioFormat.WEBM, DownloadAudioFormat.forStream(null, "https://example.test/videoplayback?mime=audio%2Fwebm&signature=x", false))
        assertEquals(DownloadAudioFormat.M4A, DownloadAudioFormat.forStream("audio/mp4", "https://example.test/audio", false))
    }

    @Test fun `HLS conversion and unknown URLs never claim lossless FLAC`() {
        assertTrue(DownloadAudioFormat.isHls("application/vnd.apple.mpegurl", "https://example.test/manifest"))
        assertFalse(DownloadAudioFormat.isHls("audio/flac", "https://example.test/stream"))
        assertEquals(DownloadAudioFormat.M4A, DownloadAudioFormat.forStream("audio/flac", "https://example.test/playlist.m3u8", true))
        assertEquals(DownloadAudioFormat.MP3, DownloadAudioFormat.forStream(null, "https://example.test/stream?filename=fake.flac", false))
    }

    @Test fun `signed query hints cannot turn a direct FLAC download into lossy HLS`() {
        val urls = listOf(
            "https://example.test/song.flac?fallback=playlist.m3u8",
            "https://example.test/song.flac?redirect=https://cdn.test/hls/manifest",
            "https://example.test/hls/song.flac?signature=123",
            "https://example.test/hlstudio/song.flac",
        )
        for (url in urls) {
            val isHls = DownloadAudioFormat.isHls("audio/flac", url)
            assertFalse(isHls, url)
            assertEquals(DownloadAudioFormat.FLAC, DownloadAudioFormat.forStream("audio/flac", url, isHls))
        }
    }

    @Test fun `HLS path and MIME detection survive signed and opaque URLs`() {
        assertTrue(DownloadAudioFormat.isHls(null, "https://example.test/PLAYLIST.M3U8?signature=123"))
        assertTrue(DownloadAudioFormat.isHls(null, "https://example.test/hls/stream?signature=123"))
        assertTrue(DownloadAudioFormat.isHls("Application/Vnd.Apple.Mpegurl; charset=utf-8", "https://example.test/stream"))
        assertFalse(DownloadAudioFormat.isHls(null, "https://example.test/hlstudio/stream"))
        assertFalse(DownloadAudioFormat.isHls(null, "https://example.test/song.flac?fallback=/hls/stream"))
    }

    @Test fun `container magic takes precedence over a legacy MP3 filename`() {
        val file = Files.createTempFile("kittytune-flac-", ".mp3")
        try {
            Files.write(file, "fLaC".toByteArray() + ByteArray(12))
            assertEquals(DownloadAudioFormat.FLAC, DownloadAudioFormat.sniff(file.toFile()))
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun `recognizes containers without confusing arbitrary or truncated data`() {
        assertEquals(DownloadAudioFormat.WAV, DownloadAudioFormat.fromHeader("RIFF0000WAVE".toByteArray()))
        assertEquals(DownloadAudioFormat.M4A, DownloadAudioFormat.fromHeader("0000ftypM4A ".toByteArray()))
        assertEquals(DownloadAudioFormat.OGG, DownloadAudioFormat.fromHeader("OggS".toByteArray()))
        assertEquals(DownloadAudioFormat.MP3, DownloadAudioFormat.fromHeader("ID3".toByteArray()))
        assertEquals(DownloadAudioFormat.WEBM, DownloadAudioFormat.fromHeader(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())))
        assertNull(DownloadAudioFormat.fromHeader(byteArrayOf()))
        assertNull(DownloadAudioFormat.fromHeader("fLa".toByteArray()))
        assertNull(DownloadAudioFormat.fromHeader("not audio".toByteArray()))
    }
}
