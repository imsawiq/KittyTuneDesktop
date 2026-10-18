package com.alananasss.kittytune.ui.profile.stats

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** Keep text readable across the whole gradient, including incompatible custom theme containers. */
internal fun readableRecapStops(stops: List<Color>, text: Color): List<Color> {
    require(stops.size >= 2)
    fun contrast(background: Color): Float {
        val a = text.luminance() + 0.05f
        val b = background.luminance() + 0.05f
        return maxOf(a, b) / minOf(a, b)
    }
    fun readable(colors: List<Color>): Boolean = colors.zipWithNext().all { (from, to) ->
        (0..32).all { step -> contrast(lerp(from, to, step / 32f)) >= 4.6f }
    }
    if (readable(stops)) return stops
    val backing = if (contrast(Color.Black) >= contrast(Color.White)) Color.Black else Color.White
    var low = 0f
    var high = 1f
    repeat(16) {
        val fraction = (low + high) / 2f
        if (readable(stops.map { lerp(it, backing, fraction) })) high = fraction else low = fraction
    }
    return stops.map { lerp(it, backing, high) }
}
