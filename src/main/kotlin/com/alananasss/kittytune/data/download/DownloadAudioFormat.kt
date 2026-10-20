package com.alananasss.kittytune.data.download

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Keep downloaded containers intact, including signed URLs with no filename extension. */
internal enum class DownloadAudioFormat(val extension: String) {
    FLAC("flac"), M4A("m4a"), MP3("mp3"), OGG("ogg"), OPUS("opus"), WEBM("webm"), WAV("wav");

    companion object {
        fun forStream(mimeType: String?, url: String, isHls: Boolean): DownloadAudioFormat {
            if (isHls) return M4A
            fromMime(mimeType)?.let { return it }
            val uri = runCatching { URI(url) }.getOrNull()
            val queryMime = uri?.rawQuery?.split('&')?.firstOrNull { it.startsWith("mime=") }
                ?.substringAfter('=')?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8) }.getOrNull() }
            fromMime(queryMime)?.let { return it }
            val path = uri?.path?.lowercase(Locale.ROOT).orEmpty()
            return entries.firstOrNull { path.endsWith(".${it.extension}") } ?: MP3
        }

        fun isHls(mimeType: String?, url: String): Boolean {
            val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            // Signed query parameters can contain another URL or an arbitrary filename. They do
            // not describe this response's container and must not trigger a lossy HLS conversion.
            val path = runCatching { URI(url).path?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
            return mime in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl") ||
                path.endsWith(".m3u8") ||
                (fromMime(mime) == null && path.split('/').any { it == "hls" })
        }

        fun sniff(file: File): DownloadAudioFormat? =
            if (!file.isFile) null else runCatching {
                file.inputStream().use { fromHeader(it.readNBytes(16)) }
            }.getOrNull()

        internal fun fromHeader(bytes: ByteArray): DownloadAudioFormat? {
            fun starts(text: String, offset: Int = 0): Boolean = bytes.size >= offset + text.length &&
                text.indices.all { bytes[offset + it] == text[it].code.toByte() }
            return when {
                starts("fLaC") -> FLAC
                starts("RIFF") && starts("WAVE", 8) -> WAV
                starts("OggS") -> OGG
                starts("ftyp", 4) -> M4A
                bytes.take(4) == listOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte()) -> WEBM
                starts("ID3") -> MP3
                bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF &&
                    (bytes[1].toInt() and 0xE0) == 0xE0 && (bytes[1].toInt() and 0x06) != 0 -> MP3
                else -> null
            }
        }

        fun fromMime(mimeType: String?): DownloadAudioFormat? = when (
            mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        ) {
            "audio/flac", "audio/x-flac" -> FLAC
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> M4A
            "audio/mpeg", "audio/mp3" -> MP3
            "audio/ogg", "application/ogg" -> OGG
            "audio/opus" -> OPUS
            "audio/webm", "video/webm" -> WEBM
            "audio/wav", "audio/wave", "audio/x-wav" -> WAV
            else -> null
        }
    }
}
