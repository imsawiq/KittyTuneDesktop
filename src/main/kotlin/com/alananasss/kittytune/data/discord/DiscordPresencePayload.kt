package com.alananasss.kittytune.data.discord

import com.alananasss.kittytune.data.local.DiscordStatusDisplay
import com.alananasss.kittytune.domain.Track
import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.net.URI
import java.util.UUID

internal object DiscordPresencePayload {
    private val gson = Gson()

    fun activity(track: Track, context: String?, playing: Boolean, positionMs: Long,
                 display: DiscordStatusDisplay, nowMs: Long = System.currentTimeMillis()): String {
        val artist = track.displayArtist.ifBlank { track.user?.username.orEmpty() }.ifBlank { "Unknown Artist" }
        val title = track.title.orEmpty().ifBlank { "Unknown Title" }
        val details = if (display == DiscordStatusDisplay.ARTIST) artist else title
        val state = when (display) {
            DiscordStatusDisplay.ARTIST -> title
            else -> "by $artist"
        }
        val activity = JsonObject().apply {
            addProperty("type", 2)
            addProperty("details", details.take(128))
            addProperty("state", (if (playing) state else "Paused · $state").take(128))
            add("assets", JsonObject().apply {
                addProperty("large_image", track.fullResArtwork.takeIf(::isWebUrl) ?: "kittytune")
                addProperty("large_text", (context ?: "KittyTune").take(128))
            })
            val duration = track.durationMs ?: 0L
            if (playing && duration > 0) {
                val start = nowMs - positionMs.coerceIn(0, duration)
                add("timestamps", JsonObject().apply {
                    addProperty("start", start / 1000)
                    addProperty("end", (start + duration) / 1000)
                })
            }
            track.permalinkUrl?.takeIf(::isWebUrl)?.let { url ->
                add("buttons", gson.toJsonTree(listOf(mapOf("label" to "Listen", "url" to url))))
            }
        }
        return activity.toString()
    }

    fun command(activity: String?): String = JsonObject().apply {
        addProperty("cmd", "SET_ACTIVITY")
        addProperty("nonce", UUID.randomUUID().toString())
        add("args", JsonObject().apply {
            addProperty("pid", ProcessHandle.current().pid())
            add("activity", activity?.let { com.google.gson.JsonParser.parseString(it) } ?: JsonNull.INSTANCE)
        })
    }.toString()

    private fun isWebUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
}
