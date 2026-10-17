package com.alananasss.kittytune.data.updates

/** HTTP range rules shared by the downloader and its regression tests. */
internal object UpdateDownloadPolicy {
    fun safeAssetName(name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) {
            "Invalid update asset name"
        }
        return name
    }

    fun resumeOffset(requestedOffset: Long, responseCode: Int, contentRange: String?): Long {
        if (responseCode == 200) return 0L // Server ignored Range: replace instead of appending.
        require(responseCode == 206 && requestedOffset > 0) { "Unexpected download response" }
        val start = contentRange?.let { Regex("bytes (\\d+)-\\d+/[\\d*]+").matchEntire(it) }
            ?.groupValues?.get(1)?.toLongOrNull()
        require(start == requestedOffset) { "Update server returned an invalid byte range" }
        return requestedOffset
    }

    fun requireComplete(actualSize: Long, expectedSize: Long) {
        require(actualSize > 0 && (expectedSize <= 0 || actualSize == expectedSize)) {
            "Update download is incomplete"
        }
    }
}
