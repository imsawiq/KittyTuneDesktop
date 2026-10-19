package com.alananasss.kittytune.data.cover.depth

import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DepthMapCacheTest {
    @Test fun `cache keys separate artwork and model revisions without exposing signed URLs`() {
        val first = DepthMapCache.key("https://example.org/a?token=private", "model-one")
        assertTrue(first.matches(Regex("[a-f0-9]{64}")))
        assertTrue(first != DepthMapCache.key("https://example.org/b?token=private", "model-one"))
        assertTrue(first != DepthMapCache.key("https://example.org/a?token=private", "model-two"))
    }

    @Test fun `stored PNG is genuinely grayscale and expires after the bounded retention period`() {
        val directory = Files.createTempDirectory("qa-depth-cache").toFile()
        try {
            val cache = DepthMapCache(directory)
            val key = DepthMapCache.key("https://example.org/cover", "test")
            val now = 1_800_000_000_000L
            val file = cache.store(key, png(), now)
            assertEquals(file, cache.find(key, now))
            assertEquals(8, javax.imageio.ImageIO.read(file).colorModel.pixelSize)
            assertNull(cache.find(key, now + DepthMapCache.TTL_MS + 1))
            assertTrue(!file.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `cache evicts old and extra entries and discards malformed cached image contents`() {
        val directory = Files.createTempDirectory("qa-depth-cache").toFile()
        try {
            val cache = DepthMapCache(directory)
            val now = 1_800_000_000_000L
            val bytes = png()
            for (index in 0..128) File(directory, "${DepthMapCache.key("cover-$index", "test")}.png").apply {
                writeBytes(bytes); setLastModified(now - index * 1000L)
            }
            cache.prune(now)
            assertEquals(128, directory.listFiles()!!.size)
            assertNotNull(cache.find(DepthMapCache.key("cover-0", "test"), now))
            assertNull(cache.find(DepthMapCache.key("cover-128", "test"), now))
            val badKey = DepthMapCache.key("bad", "test")
            File(directory, "$badKey.png").apply { writeBytes(ByteArray(50)); setLastModified(now) }
            assertNull(cache.find(badKey, now))
        } finally { directory.deleteRecursively() }
    }

    private fun png(): ByteArray = ByteArrayOutputStream().also {
        javax.imageio.ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY), "png", it)
    }.toByteArray()
}
