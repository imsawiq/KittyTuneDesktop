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
        val range = contentRange?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(it) }
        val start = range?.groupValues?.get(1)?.toLongOrNull()
        val end = range?.groupValues?.get(2)?.toLongOrNull()
        val total = range?.groupValues?.get(3)?.takeUnless { it == "*" }?.toLongOrNull()
        require(start == requestedOffset && end != null && end >= requestedOffset &&
            (range?.groupValues?.get(3) == "*" || (total != null && total > end))) {
            "Update server returned an invalid byte range"
        }
        return requestedOffset
    }

    fun requireComplete(actualSize: Long, expectedSize: Long) {
        require(actualSize > 0 && (expectedSize <= 0 || actualSize == expectedSize)) {
            "Update download is incomplete"
        }
    }
}
