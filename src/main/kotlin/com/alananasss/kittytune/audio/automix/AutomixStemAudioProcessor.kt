package com.alananasss.kittytune.audio.automix

import com.alananasss.kittytune.audio.BaseAudioProcessor
import com.alananasss.kittytune.audio.ai.karaoke.CachedStemReader
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/** Cached-only outgoing vocal attenuation. User effects and deck volume remain separate. */
internal class AutomixStemAudioProcessor : BaseAudioProcessor() {
    @Volatile private var reader: CachedStemReader? = null
    private var owner = -1L
    private var samplePosition = 0L
    @Volatile private var suppression = 0f
    val isActive: Boolean get() = reader != null && suppression > 0f
    private val left = FloatArray(BLOCK_FRAMES)
    private val right = FloatArray(BLOCK_FRAMES)

    @Synchronized
    fun install(prepared: CachedStemReader, generation: Long) {
        clear()
        reader = prepared
        owner = generation
    }

    @Synchronized
    fun setSuppression(generation: Long, amount: Float) {
        if (generation == owner) suppression = if (amount.isFinite()) amount.coerceIn(0f, 1f) else 0f
    }

    @Synchronized
    fun clear(generation: Long? = null) {
        if (generation != null && generation != owner) return
        suppression = 0f
        reader?.close()
        reader = null
        owner = -1L
    }

    @Synchronized
    fun seekToPositionMs(positionMs: Long) {
        alignSourceTimestampUs(positionMs.coerceAtLeast(0L) * 1000L)
    }

    @Synchronized
    fun alignSourceTimestampUs(timestampUs: Long) {
        samplePosition = timestampUs.coerceAtLeast(0L) * 44100L / 1_000_000L
    }

    override fun onFlush() = clear()
    override fun onReset() = clear()

    @Synchronized
    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        if (inputAudioFormat.sampleRate != 44100 || inputAudioFormat.channelCount != 2 || remaining % 4 != 0) {
            output.put(inputBuffer).flip()
            clear()
            return
        }
        var framesLeft = remaining / 4
        while (framesLeft > 0) {
            val count = minOf(framesLeft, BLOCK_FRAMES)
            val access = reader
            val mix = suppression
            val useStem = mix > 0f && access != null && access.readSlice(samplePosition, left, right, count)
            if (mix > 0f && access != null && !useStem) clear()
            for (frame in 0 until count) {
                val dryLeft = inputBuffer.short
                val dryRight = inputBuffer.short
                if (useStem) {
                    output.putShort(blend(dryLeft, left[frame], mix))
                    output.putShort(blend(dryRight, right[frame], mix))
                } else {
                    output.putShort(dryLeft)
                    output.putShort(dryRight)
                }
            }
            samplePosition += count
            framesLeft -= count
        }
        output.flip()
    }

    private fun blend(dry: Short, instrumental: Float, mix: Float): Short =
        (dry * (1f - mix) + instrumental * 32768f * mix).toInt().coerceIn(-32768, 32767).toShort()

    companion object {
        private const val BLOCK_FRAMES = 4096

        /** Called on IO, never from queueInput. Ownership transfers to the engine on installation. */
        fun openCachedStem(file: File, durationMs: Long, startMs: Long, endMs: Long): CachedStemReader? {
            if (durationMs <= 0L || startMs < 0L || endMs <= startMs || endMs > durationMs) return null
            val access = CachedStemReader.open(file) ?: return null
            val actualDurationMs = access.totalFrames * 1000L / access.sampleRate
            if (access.sampleRate != 44100 || access.channels != 2 ||
                abs(actualDurationMs - durationMs) > 1000L || access.totalFrames < (endMs * 44100L + 999L) / 1000L) {
                access.close()
                return null
            }
            return access
        }
    }
}
