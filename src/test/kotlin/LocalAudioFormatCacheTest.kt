package com.alananasss.kittytune.data.download

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class LocalAudioFormatCacheTest {
    @Test fun `file signature wins over filename and nonexistent files have no quality`() {
        val directory = Files.createTempDirectory("kittytune-local-format")
        try {
            val disguisedFlac = directory.resolve("song.mp3")
            val disguisedMp3 = directory.resolve("song.flac")
            Files.write(disguisedFlac, "fLaC".toByteArray())
            Files.write(disguisedMp3, "ID3".toByteArray())
            val cache = LocalAudioFormatCache()
            assertEquals(DownloadAudioFormat.FLAC, cache.formatFor(disguisedFlac.toString()))
            assertEquals(DownloadAudioFormat.MP3, cache.formatFor(disguisedMp3.toString()))
            assertNull(cache.formatFor(directory.resolve("missing.flac").toString()))
            assertNull(cache.formatFor(null))
            assertNull(cache.formatFor(directory.toString()))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun `replacement file and download path changes invalidate old evidence`() {
        val directory = Files.createTempDirectory("kittytune-local-format")
        try {
            val path = directory.resolve("track.audio")
            val other = directory.resolve("new-track.audio")
            val cache = LocalAudioFormatCache()
            Files.write(path, "fLaC".toByteArray())
            assertEquals(DownloadAudioFormat.FLAC, cache.formatFor(path.toString()))
            val previousTime = Files.getLastModifiedTime(path).toMillis()
            Files.write(path, "ID3 ".toByteArray())
            Files.setLastModifiedTime(path, FileTime.fromMillis(previousTime + 2000))
            assertEquals(DownloadAudioFormat.MP3, cache.formatFor(path.toString()))
            Files.write(other, "RIFF0000WAVE".toByteArray())
            assertEquals(DownloadAudioFormat.WAV, cache.formatFor(other.toString()))
            Files.delete(path)
            assertNull(cache.formatFor(path.toString()))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun `unknown cached entries are evicted at the same bound as known formats`() {
        val directory = Files.createTempDirectory("kittytune-local-format")
        try {
            val first = directory.resolve("first.audio")
            val second = directory.resolve("second.audio")
            Files.write(first, "none".toByteArray())
            Files.write(second, "ID3 ".toByteArray())
            val cache = LocalAudioFormatCache(maximumEntries = 1)
            assertNull(cache.formatFor(first.toString()))
            val originalTime = Files.getLastModifiedTime(first)
            assertEquals(DownloadAudioFormat.MP3, cache.formatFor(second.toString()))
            Files.write(first, "fLaC".toByteArray())
            Files.setLastModifiedTime(first, originalTime)
            assertEquals(DownloadAudioFormat.FLAC, cache.formatFor(first.toString()))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
