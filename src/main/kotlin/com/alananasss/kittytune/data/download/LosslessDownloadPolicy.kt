package com.alananasss.kittytune.data.download

import java.io.File

internal class LosslessUnavailableException : Exception("FLAC stream unavailable")

internal object LosslessDownloadPolicy {
    fun acceptsStream(mimeType: String?, url: String): Boolean =
        !DownloadAudioFormat.isHls(mimeType, url) &&
            DownloadAudioFormat.fromMime(mimeType) == DownloadAudioFormat.FLAC

    fun acceptsFile(file: File): Boolean = DownloadAudioFormat.sniff(file) == DownloadAudioFormat.FLAC
}
