package com.alananasss.kittytune.data

import com.alananasss.kittytune.data.discord.DiscordIpcConnection
import com.alananasss.kittytune.data.discord.DiscordPresencePayload
import com.alananasss.kittytune.data.local.PlayerPreferences
import com.alananasss.kittytune.domain.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Rich Presence uses the running Discord desktop client, without login or user tokens. */
class DiscordRPC {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updates = Channel<String>(Channel.CONFLATED)
    @Volatile private var connection: DiscordIpcConnection? = null

    init {
        scope.launch {
            var latest: String? = null
            var sent: String? = null
            try {
                while (isActive) {
                    val received = withTimeoutOrNull(5_000) { updates.receive() }
                    if (received != null) latest = received
                    val activity = latest ?: continue
                    if (connection?.isOpen != true) {
                        _status.value = Status.CONNECTING
                        connection?.close()
                        connection = DiscordIpcConnection.connect(APPLICATION_ID)
                        sent = null
                        _status.value = if (connection == null) Status.DISCONNECTED else Status.CONNECTED
                        if (connection == null) { delay(10_000); continue }
                    }
                    if (sent != activity) {
                        try {
                            connection?.send(DiscordPresencePayload.command(activity))
                            sent = activity
                            // Discord rate-limits SET_ACTIVITY; coalesce rapid playback/seek changes.
                            delay(5_000)
                        } catch (_: java.io.IOException) {
                            connection?.close()
                            connection = null
                            _status.value = Status.DISCONNECTED
                        } catch (_: IllegalStateException) {
                            connection?.close()
                            connection = null
                            _status.value = Status.DISCONNECTED
                        }
                    }
                }
            } finally {
                connection?.close()
                connection = null
            }
        }
    }

    suspend fun updatePresence(track: Track, contextName: String?, isPlaying: Boolean, position: Long) {
        val payload = DiscordPresencePayload.activity(
            track, contextName, isPlaying, position, PlayerPreferences().getDiscordStatusDisplay())
        updates.trySend(payload)
    }

    fun clearPresence() {
        updates.trySend("null")
    }

    fun closeRPC() {
        // Closing the IPC transport clears this application's activity in the client.
        scope.cancel()
        connection?.close()
        updates.close()
        _status.value = Status.DISABLED
    }

    enum class Status { DISABLED, CONNECTING, CONNECTED, DISCONNECTED }
    companion object {
        private const val APPLICATION_ID = "1473071817693331540"
        private val _status = MutableStateFlow(Status.DISABLED)
        val status = _status.asStateFlow()
    }
}
