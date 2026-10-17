package com.alananasss.kittytune.ui.player.lyrics

/** Preserve the chosen size on roomy panels; keep a few readable rows in a constrained viewport. */
internal fun panelLyricsFontSize(requestedSp: Float, widthDp: Float, heightDp: Float): Float {
    val widthScale = if (widthDp.isFinite()) widthDp / 320f else 1f
    val heightScale = if (heightDp.isFinite()) heightDp / 260f else 1f
    val scale = minOf(widthScale, heightScale).coerceIn(0.72f, 1f)
    return (requestedSp * scale).coerceAtLeast(minOf(12f, requestedSp))
}
