package com.alananasss.kittytune.data.cover.depth

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.imageio.ImageIO

internal class DepthMapCache(private val directory: File) {
    fun find(key: String, now: Long): File? {
        val file = entry(key)
        if (!file.isFile) return null
        if (now - file.lastModified() !in 0..TTL_MS || !isDepthPng(file)) { file.delete(); return null }
        file.setLastModified(now)
        return file
    }

    fun store(key: String, bytes: ByteArray, now: Long): File {
        require(bytes.size in 26..MAX_ENTRY_BYTES)
        directory.mkdirs()
        val target = entry(key)
        val temporary = File.createTempFile("depth-", ".part", directory)
        try {
            temporary.writeBytes(bytes)
            require(isDepthPng(temporary))
            replace(temporary, target)
            target.setLastModified(now)
            prune(now)
            return target
        } finally { temporary.delete() }
    }

    fun prune(now: Long) {
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "png" }
            ?.sortedByDescending { it.lastModified() } ?: return
        var count = 0
        var bytes = 0L
        for (file in files) {
            if (now - file.lastModified() !in 0..TTL_MS || count >= MAX_ENTRIES || bytes + file.length() > MAX_BYTES) file.delete()
            else { count++; bytes += file.length() }
        }
        directory.listFiles()?.filter { it.isFile && it.extension == "part" && now - it.lastModified() > 86_400_000L }
            ?.forEach { it.delete() }
    }

    private fun entry(key: String): File {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "$key.png")
    }

    private fun isDepthPng(file: File): Boolean {
        if (file.length() !in 26L..MAX_ENTRY_BYTES.toLong()) return false
        val header = file.inputStream().use { it.readNBytes(26) }
        if (header.size != 26 || !header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return false
        val width = ByteBuffer.wrap(header, 16, 4).int
        val height = ByteBuffer.wrap(header, 20, 4).int
        if (width !in 1..512 || height !in 1..512 || header[24] != 8.toByte() || header[25] != 0.toByte()) return false
        return runCatching { ImageIO.read(file)?.let { it.width == width && it.height == height && it.raster.numBands == 1 } == true }
            .getOrDefault(false)
    }

    companion object {
        const val MAX_ENTRIES = 128
        const val MAX_BYTES = 64L * 1024 * 1024
        const val MAX_ENTRY_BYTES = 1024 * 1024
        const val TTL_MS = 30L * 24 * 60 * 60 * 1000
        private val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        fun key(url: String, modelVersion: String): String = sha256(("$modelVersion:$url").toByteArray(Charsets.UTF_8))
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
        fun replace(source: File, target: File) {
            try { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
    }
}
