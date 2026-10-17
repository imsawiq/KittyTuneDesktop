package com.alananasss.kittytune.ui.player

/** Destination after removing the selected occurrence, keeping the playing track in place. */
internal fun queuePlayNextDestination(queueSize: Int, currentIndex: Int, selectedIndex: Int): Int? {
    if (currentIndex !in 0 until queueSize || selectedIndex !in 0 until queueSize) return null
    if (selectedIndex == currentIndex) return null
    return if (selectedIndex < currentIndex) currentIndex else currentIndex + 1
}
