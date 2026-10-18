package com.alananasss.kittytune.ui.player.cover

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/** A smooth mask with a flat, sharp centre; zero strength leaves the cover untouched. */
internal fun Modifier.featheredArtwork(amount: Float): Modifier {
    val strength = amount.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    if (strength == 0f) return this
    val fraction = 0.45f * strength
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            // Smoothstep has no abrupt change of slope where the soft edge meets the sharp centre.
            val edge = (0..16).map { step ->
                val t = step / 16f
                fraction * t to Color.Black.copy(alpha = t * t * (3f - 2f * t))
            }
            val stops = (edge + edge.reversed().map { (position, color) -> 1f - position to color }).toTypedArray()
            val across = Brush.horizontalGradient(*stops)
            val down = Brush.verticalGradient(*stops)
            onDrawWithContent {
                drawContent()
                drawRect(across, blendMode = BlendMode.DstIn)
                drawRect(down, blendMode = BlendMode.DstIn)
            }
        }
}
