package com.alananasss.kittytune.ui.player

import com.alananasss.kittytune.domain.Track

/** Bind a displayed snapshot to its current slot without confusing equal duplicate occurrences. */
internal fun queueMenuOccurrence(queue: List<Track>, index: Int, displayed: Track): Track? =
    queue.getOrNull(index)?.takeIf { it.isSameQueueTrack(displayed) }

/** Destination after removing the selected occurrence, keeping the playing track in place. */
internal fun queuePlayNextDestination(queueSize: Int, currentIndex: Int, selectedIndex: Int): Int? {
    if (currentIndex !in 0 until queueSize || selectedIndex !in 0 until queueSize) return null
    if (selectedIndex == currentIndex) return null
    return if (selectedIndex < currentIndex) currentIndex else currentIndex + 1
}
