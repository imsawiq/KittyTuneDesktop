package com.alananasss.kittytune.data.artist.portrait

import java.io.File
import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortraitCacheTest {
    @Test fun `cache evicts expired portraits and retains no more than 128 most recent files`() {
        val directory = Files.createTempDirectory("portrait-cache-test").toFile()
        try {
            val cache = PortraitCache(directory)
            val now = 1_800_000_000_000L
            val expired = File(directory, "old.png").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(now - PortraitCache.TTL_MILLIS - 1000)
            }
            for (index in 0..128) File(directory, "$index.png").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(now - index * 1000L)
            }
            cache.prune(now)
            assertTrue(!expired.exists())
            assertEquals(128, directory.listFiles()!!.size)
            assertNotNull(cache.find("0", now))
            assertNull(cache.find("128", now))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `cache digest separates remote URLs into safe fixed length file names`() {
        val digest = PortraitCache.digest("https://example.org/image?token=abc".toByteArray())
        assertTrue(digest.matches(Regex("[a-f0-9]{64}")))
        assertTrue(digest != PortraitCache.digest("https://example.org/other".toByteArray()))
    }
}
