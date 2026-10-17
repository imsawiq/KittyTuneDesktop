package com.alananasss.kittytune.audio.automix

/** Automix still transitions when an audio analysis is unavailable or too uncertain. */
internal object TransitionFallback {
    fun durationMs(crossfadeEnabled: Boolean, automixEnabled: Boolean, configuredSeconds: Int): Long = when {
        automixEnabled -> configuredSeconds.coerceAtLeast(3) * 1_000L
        crossfadeEnabled -> configuredSeconds.coerceAtLeast(0) * 1_000L
        else -> 0L
    }
}
