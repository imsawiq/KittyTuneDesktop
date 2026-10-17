package com.alananasss.kittytune.data.lyrics.publishing

import com.google.gson.JsonObject

/** The legacy plain/LRC fields remain accepted by LRCLIB's current publish endpoint. Duration is seconds. */
data class LyricsPublishDraft(
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSeconds: Double,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
) {
    internal fun validationError(): String? = when {
        trackName.isBlank() || artistName.isBlank() -> "Track and artist names are required"
        listOf(trackName, artistName, albumName).any { it.length > 1024 || it.any { char -> char.code < 32 } } -> "Invalid track metadata"
        !durationSeconds.isFinite() || durationSeconds <= 0.0 || durationSeconds > 86_400.0 -> "Invalid duration"
        plainLyrics.isNullOrBlank() && syncedLyrics.isNullOrBlank() -> "Lyrics are required"
        listOfNotNull(plainLyrics, syncedLyrics).any { it.length > MAX_LYRICS_CHARS } -> "Lyrics exceed the publish size limit"
        else -> null
    }

    internal fun payload(): String = JsonObject().apply {
        addProperty("trackName", trackName.trim())
        addProperty("artistName", artistName.trim())
        addProperty("albumName", albumName.trim())
        addProperty("duration", durationSeconds)
        plainLyrics?.takeIf { it.isNotBlank() }?.let { addProperty("plainLyrics", it) }
        syncedLyrics?.takeIf { it.isNotBlank() }?.let { addProperty("syncedLyrics", it) }
    }.toString()

    private companion object { const val MAX_LYRICS_CHARS = 131_072 }
}

sealed interface LyricsPublishResult {
    data object Published : LyricsPublishResult
    data class InvalidDraft(val reason: String) : LyricsPublishResult
    data object InvalidChallenge : LyricsPublishResult
    data object WorkLimitReached : LyricsPublishResult
    data object TimedOut : LyricsPublishResult
    data object NetworkFailure : LyricsPublishResult
    data object ResponseTooLarge : LyricsPublishResult
    data class HttpRejected(
        val stage: Stage,
        val statusCode: Int,
        val errorName: String?,
        val retryAfterSeconds: Long?,
    ) : LyricsPublishResult
    enum class Stage { CHALLENGE, PUBLISH }
}
