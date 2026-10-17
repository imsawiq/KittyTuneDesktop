package com.alananasss.kittytune.ui.player.lyrics.timing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive

/** One playback clock for word reveals in the panel, mini player and full lyrics views. */
@Composable
internal fun rememberSmoothPosition(positionMs: () -> Long, isPlaying: Boolean, speed: Float): State<Float> {
    val positionReader by rememberUpdatedState(positionMs)
    val smooth = remember { mutableFloatStateOf(positionMs().toFloat()) }
    LaunchedEffect(isPlaying, speed) {
        if (!isPlaying) {
            snapshotFlow { positionReader() }.collect { smooth.floatValue = it.toFloat() }
            return@LaunchedEffect
        }
        val clock = LyricsPositionClock()
        while (isActive) {
            withFrameNanos {
                smooth.floatValue = clock.step(positionReader(), speed, System.nanoTime())
            }
        }
    }
    return smooth
}
