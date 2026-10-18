package com.alananasss.kittytune.data.artist.portrait

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal class PortraitCache(private val directory: File) {
    fun find(key: String, now: Long): File? {
        val file = File(directory, "$key.png")
        return file.takeIf { it.isFile && it.length() > 0 && now - it.lastModified() in 0..TTL_MILLIS }
            ?.also { it.setLastModified(now) }
    }

    fun store(key: String, bytes: ByteArray, now: Long): File {
        require(bytes.size <= MAX_BYTES)
        directory.mkdirs()
        val target = File(directory, "$key.png")
        val temporary = File.createTempFile("portrait-", ".part", directory)
        try {
            temporary.writeBytes(bytes)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            target.setLastModified(now)
            prune(now)
            return target
        } finally {
            temporary.delete()
        }
    }

    fun prune(now: Long) {
        val files = directory.listFiles()?.filter { it.extension == "png" }?.sortedByDescending { it.lastModified() } ?: return
        var retainedBytes = 0L
        var retainedCount = 0
        for (file in files) {
            if (now - file.lastModified() !in 0..TTL_MILLIS || retainedCount >= MAX_ENTRIES || retainedBytes + file.length() > MAX_BYTES) {
                file.delete()
            } else {
                retainedBytes += file.length()
                retainedCount++
            }
        }
    }

    companion object {
        const val MAX_ENTRIES = 128
        const val MAX_BYTES = 64 * 1024 * 1024
        const val TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
