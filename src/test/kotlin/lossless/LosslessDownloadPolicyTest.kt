package com.alananasss.kittytune.data.download

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LosslessDownloadPolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `strict downloads reject HLS and lossy streams even with a FLAC filename`() {
        assertTrue(LosslessDownloadPolicy.acceptsStream("audio/x-flac", "https://example.test/stream?fallback=/hls"))
        assertFalse(LosslessDownloadPolicy.acceptsStream("audio/mpeg", "https://example.test/song.flac"))
        assertFalse(LosslessDownloadPolicy.acceptsStream("audio/flac", "https://example.test/list.m3u8"))
        assertFalse(LosslessDownloadPolicy.acceptsStream(null, "https://example.test/song.flac"))
    }

    @Test fun `downloaded bytes determine strict acceptance rather than MIME or extension`() {
        val fake = temporary.newFile("fake.flac").apply { writeBytes("ID3not-lossless".toByteArray()) }
        val flac = temporary.newFile("signed-stream").apply { writeBytes("fLaCverified-payload".toByteArray()) }
        assertFalse(LosslessDownloadPolicy.acceptsFile(fake))
        assertTrue(LosslessDownloadPolicy.acceptsFile(flac))
        assertFalse(LosslessDownloadPolicy.acceptsFile(temporary.root.resolve("absent.flac")))
    }
}
