package com.alananasss.kittytune.data.sync

/** An occurrence index belongs to the queue seen when its menu opened, even if a newer state arrives. */
internal fun connectQueueCommandVersion(action: String, capturedVersion: String?, latestVersion: String): String =
    if (action in listOf("queue", "remove", "move", "move_next")) capturedVersion ?: latestVersion else ""
