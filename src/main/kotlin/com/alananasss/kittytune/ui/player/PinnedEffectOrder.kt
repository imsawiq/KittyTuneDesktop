package com.alananasss.kittytune.ui.player

/** Move the existing effect; a drag never adds a second occurrence. */
internal fun movePinnedEffect(order: List<String>, id: String, targetIndex: Int): List<String> {
    val sourceIndex = order.indexOf(id)
    if (sourceIndex < 0) return order
    val destination = targetIndex.coerceIn(0, order.lastIndex)
    if (sourceIndex == destination) return order
    return order.toMutableList().apply {
        removeAt(sourceIndex)
        add(destination, id)
    }
}
