package com.alananasss.kittytune.data.discord

import java.io.Closeable

/** One local desktop IPC session; no account credentials are part of this contract. */
internal interface DiscordPresenceTransport : Closeable {
    val isOpen: Boolean
    fun send(command: String)
}
