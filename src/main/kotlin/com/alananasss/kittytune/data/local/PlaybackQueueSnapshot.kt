package com.alananasss.kittytune.data.local

import com.alananasss.kittytune.domain.Track
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.IdentityHashMap

/** Saves the displayed order once, with a permutation for restoring the unshuffled order. */
data class PlaybackQueueSnapshot(
    val tracks: List<Track> = emptyList(),
    val originalOrder: List<Int> = emptyList(),
) {
    fun originalTracks(): List<Track> =
        if (originalOrder.size == tracks.size && originalOrder.toSet() == tracks.indices.toSet()) {
            originalOrder.map(tracks::get)
        } else tracks

    fun currentIndex(savedIndex: Int, trackId: Long): Int =
        savedIndex.takeIf { tracks.getOrNull(it)?.id == trackId }
            ?: tracks.indexOfFirst { it.id == trackId }

    companion object {
        fun capture(tracks: List<Track>, original: List<Track>): PlaybackQueueSnapshot {
            val indices = IdentityHashMap<Track, ArrayDeque<Int>>()
            tracks.forEachIndexed { index, track -> indices.getOrPut(track) { ArrayDeque() }.addLast(index) }
            val order = original.mapNotNull { indices[it]?.removeFirstOrNull() }
            return PlaybackQueueSnapshot(tracks.toList(), order)
        }

        fun decode(json: String, gson: Gson): PlaybackQueueSnapshot {
            // Queue caches before this format were bare arrays containing the original order.
            return if (json.trimStart().startsWith("[")) {
                val type = object : TypeToken<List<Track>>() {}.type
                PlaybackQueueSnapshot(gson.fromJson<List<Track>>(json, type) ?: emptyList())
            } else {
                gson.fromJson(json, PlaybackQueueSnapshot::class.java) ?: PlaybackQueueSnapshot()
            }
        }
    }
}
