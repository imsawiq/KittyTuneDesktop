package com.alananasss.kittytune.data.download

import java.io.File

/** Small header cache for visible library rows. Call from an IO dispatcher. */
internal class LocalAudioFormatCache(private val maximumEntries: Int = 256) {
    init { require(maximumEntries > 0) }

    private data class FileIdentity(val path: String, val length: Long, val modifiedAt: Long)

    private val formats = object : LinkedHashMap<FileIdentity, DownloadAudioFormat?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FileIdentity, DownloadAudioFormat?>): Boolean =
            size > maximumEntries
    }

    @Synchronized
    fun formatFor(path: String?): DownloadAudioFormat? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        val identity = FileIdentity(file.absoluteFile.normalize().path, file.length(), file.lastModified())
        if (formats.containsKey(identity)) return formats[identity]
        return DownloadAudioFormat.sniff(file).also { formats[identity] = it }
    }
}
