package com.alananasss.kittytune.ui.player

import com.alananasss.kittytune.domain.Track

data class QueueMenuStatus(
    val futureCount: Int,
    val isRepeatArmed: Boolean,
    val isRepeating: Boolean,
    val isCountLowerBound: Boolean = false,
) {
    val canAdd: Boolean get() = !isRepeatArmed && !isRepeating
    val action: QueueMenuAction get() = when {
        !canAdd -> QueueMenuAction.NONE
        futureCount >= 2 -> QueueMenuAction.ARM_REPEAT
        else -> QueueMenuAction.INSERT_NEXT
    }
}

enum class QueueMenuAction { INSERT_NEXT, ARM_REPEAT, NONE }
internal enum class QueuedRepeatDisposition { WAIT, ACTIVATE, CANCEL }

internal fun Track.isSameQueueTrack(other: Track?): Boolean = other != null && id == other.id && source == other.source

internal fun futureQueueOccurrences(queue: List<Track>, currentIndex: Int, selected: Track): List<Track> =
    queue.drop((currentIndex + 1).coerceAtLeast(0)).filter { it.isSameQueueTrack(selected) }

/** The selected occurrence survives reordering; skipping past or deleting it cancels the armed repeat. */
internal fun queuedRepeatDisposition(
    target: Track,
    queue: List<Track>,
    currentIndex: Int,
    playingTrack: Track?,
): QueuedRepeatDisposition {
    val targetIndex = queue.indexOfFirst { it === target }
    return when {
        targetIndex < 0 || targetIndex < currentIndex -> QueuedRepeatDisposition.CANCEL
        targetIndex == currentIndex && target.isSameQueueTrack(playingTrack) -> QueuedRepeatDisposition.ACTIVATE
        else -> QueuedRepeatDisposition.WAIT
    }
}
