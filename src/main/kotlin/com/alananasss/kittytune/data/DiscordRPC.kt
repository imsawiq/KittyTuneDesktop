package com.alananasss.kittytune.data

import com.alananasss.kittytune.data.discord.DiscordIpcConnection
import com.alananasss.kittytune.data.discord.DiscordPresencePayload
import com.alananasss.kittytune.data.discord.DiscordPresenceTransport
import com.alananasss.kittytune.data.local.DiscordStatusDisplay
import com.alananasss.kittytune.data.local.PlayerPreferences
import com.alananasss.kittytune.domain.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Rich Presence uses the running Discord desktop client, without login or user tokens. */
class DiscordRPC internal constructor(
    private val scope: CoroutineScope,
    private val connect: () -> DiscordPresenceTransport?,
    private val statusDisplay: () -> DiscordStatusDisplay,
) {
    constructor() : this(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        { DiscordIpcConnection.connect(APPLICATION_ID) },
        { PlayerPreferences().getDiscordStatusDisplay() },
    )

    private val updates = Channel<String>(Channel.CONFLATED)
    private val lifecycleLock = Any()
    @Volatile private var closed = false
    @Volatile private var connection: DiscordPresenceTransport? = null

    init {
        scope.launch {
            var latest: String? = null
            var sent: String? = null
            try {
                while (isActive) {
                    val received = withTimeoutOrNull(5_000) { updates.receive() }
                    if (received != null) latest = received
                    var activity = latest ?: continue
                    // Clearing an idle session must not open Discord just to publish null.
                    if (activity == "null" && connection?.isOpen != true) continue
                    if (connection?.isOpen != true) {
                        setStatus(Status.CONNECTING)
                        connection?.close()
                        val connected = connect()
                        val adopted = synchronized(lifecycleLock) {
                            if (closed || !isActive) false else {
                                connection = connected
                                _status.value = if (connected == null) Status.DISCONNECTED else Status.CONNECTED
                                true
                            }
                        }
                        if (!adopted) {
                            connected?.close()
                            break
                        }
                        sent = null
                        if (connection == null) { delay(10_000); continue }
                    }
                    ensureActive()
                    // A handshake may take seconds. Publish the latest track/clear request,
                    // rather than resurrecting the activity that initiated the connection.
                    updates.tryReceive().getOrNull()?.let { latest = it; activity = it }
                    if (sent != activity) {
                        try {
                            connection?.send(DiscordPresencePayload.command(activity))
                            sent = activity
                            // Discord rate-limits SET_ACTIVITY; coalesce rapid playback/seek changes.
                            delay(5_000)
                        } catch (_: java.io.IOException) {
                            connection?.close()
                            connection = null
                            setStatus(Status.DISCONNECTED)
                        } catch (_: IllegalStateException) {
                            connection?.close()
                            connection = null
                            setStatus(Status.DISCONNECTED)
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
            track, contextName, isPlaying, position, statusDisplay())
        updates.trySend(payload)
    }

    fun clearPresence() {
        updates.trySend("null")
    }

    fun closeRPC() {
        // Closing the IPC transport clears this application's activity in the client.
        val current = synchronized(lifecycleLock) {
            closed = true
            _status.value = Status.DISABLED
            connection.also { connection = null }
        }
        scope.cancel()
        current?.close()
        updates.close()
    }

    private fun setStatus(status: Status) = synchronized(lifecycleLock) {
        if (!closed) _status.value = status
    }

    enum class Status { DISABLED, CONNECTING, CONNECTED, DISCONNECTED }
    companion object {
        private const val APPLICATION_ID = "1473071817693331540"
        private val _status = MutableStateFlow(Status.DISABLED)
        val status = _status.asStateFlow()
    }
}
