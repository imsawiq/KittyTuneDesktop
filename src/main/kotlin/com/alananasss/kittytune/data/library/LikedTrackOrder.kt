package com.alananasss.kittytune.data.library

import com.alananasss.kittytune.core.NamedPrefs
import com.alananasss.kittytune.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** A local arrangement of liked IDs, independent of like dates and server synchronization. */
class LikedTrackOrder(private val preferences: NamedPrefs = NamedPrefs("liked_track_order")) {
    private val writeLock = Any()
    private val mutableIds = MutableStateFlow(
        preferences.getString("ids", "").orEmpty().split(',').mapNotNull(String::toLongOrNull).distinct(),
    )
    val ids = mutableIds.asStateFlow()

    fun move(tracks: List<Track>, fromId: Long, toId: Long) {
        mutableIds.update { saved ->
            val ordered = orderLikedTracks(tracks, saved).map { it.id }.toMutableList()
            val from = ordered.indexOf(fromId)
            val to = ordered.indexOf(toId)
            if (from >= 0 && to >= 0 && from != to) ordered.add(to, ordered.removeAt(from))
            ordered
        }
    }

    /** Only the finished drag writes to disk; a disposed screen must not cancel that write. */
    suspend fun persist() = withContext(NonCancellable + Dispatchers.IO) {
        synchronized(writeLock) {
            preferences.putString("ids", mutableIds.value.joinToString(","))
        }
    }

    /** A navigation away from an unfinished drag still commits its latest arrangement. */
    fun persistInBackground() {
        persistenceScope.launch { persist() }
    }

    /** Logout clears the arrangement with the liked library, including any queued write. */
    fun clear() = synchronized(writeLock) {
        mutableIds.value = emptyList()
        preferences.remove("ids")
    }

    companion object {
        private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val shared by lazy { LikedTrackOrder() }
    }
}

/** New likes appear first; surviving arranged tracks keep their relative positions. */
internal fun orderLikedTracks(tracks: List<Track>, savedIds: List<Long>): List<Track> {
    val available = tracks.associateBy { it.id }
    val saved = savedIds.distinct().mapNotNull(available::get)
    val arrangedIds = saved.mapTo(HashSet()) { it.id }
    return tracks.filter { it.id !in arrangedIds } + saved
}
