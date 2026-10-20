package com.alananasss.kittytune

import com.alananasss.kittytune.audio.AudioEngine
import com.alananasss.kittytune.audio.ai.AiDetectionManager
import com.alananasss.kittytune.data.local.PlayerPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.lang.reflect.Proxy
import javax.sound.sampled.SourceDataLine
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AudioEnginePrimingTest {
    @Test
    fun pausedPrimingHoldsExactPcmWithoutWritingOrAdvancingTheClock() {
        val engine = AudioEngine()
        val written = mutableListOf<Byte>()
        val line = line(written)
        setField(engine, "line", line)
        try {
            engine.preparePrimedHead() // No URL means no decode worker/device is opened.
            val head = ShortArray(engine.primedHeadSamples) { shortArrayOf(1, -2, 32767, -32768)[it % 4] }
            val expected = head.flatMap { listOf((it.toInt() and 255).toByte(), (it.toInt() shr 8).toByte()) }.toByteArray()
            assertTrue(engine.holdPrimedAudio(head, head.size, 0L, 41L))
            head[0] = 999
            assertTrue(engine.hasPrimedAudio)
            assertFalse(engine.hasBufferedAudio)
            assertEquals(0L, engine.positionMs)
            assertTrue(written.isEmpty())
            assertFalse(engine.holdPrimedAudio(shortArrayOf(9, 9), 2, 0L, 50L))

            engine.play()
            engine.writePrimedAudio(line)
            assertContentEquals(expected, written.toByteArray())
            assertFalse(engine.hasPrimedAudio)
            assertTrue(engine.hasBufferedAudio)
            assertEquals(41L, engine.positionMs)
            engine.writePrimedAudio(line)
            assertEquals(expected.size, written.size)
        } finally {
            engine.release()
        }
    }

    @Test
    fun heldHeadsAreBoundedAndInvalidatedBySeekAndStop() {
        val engine = AudioEngine()
        val written = mutableListOf<Byte>()
        val line = line(written)
        setField(engine, "line", line)
        try {
            engine.preparePrimedHead()
            assertFalse(engine.holdPrimedAudio(ShortArray(engine.primedHeadSamples + 2), engine.primedHeadSamples + 2, 0L, 50L))
            assertTrue(engine.holdPrimedAudio(ShortArray(engine.primedHeadSamples), engine.primedHeadSamples, 0L, 50L))
            engine.seekTo(100L)
            assertFalse(engine.hasPrimedAudio)
            engine.writePrimedAudio(line)
            assertTrue(written.isEmpty())
            assertFalse(engine.holdPrimedAudio(shortArrayOf(1, 2), 2, 0L, 50L))
            engine.stop()
            assertFalse(engine.hasPrimedAudio)
        } finally {
            engine.release()
        }
    }

    @Test
    fun speculativePreparationAndOldEngineDisposalPreserveTheCurrentAiVerdict() {
        withDisabledAiRequests {
            val active = AudioEngine()
            val speculative = AudioEngine()
            val verdict = AiDetectionManager.Result(0.94f, true, AiDetectionManager.Status.DONE)
            try {
                setVerdict(verdict)
                speculative.setMediaItem("prepared-next", startPositionMs = 15_000L, announceTrackChange = false)
                assertSame(verdict, AiDetectionManager.result.value)
                speculative.release()
                assertSame(verdict, AiDetectionManager.result.value)
                active.setMediaItem("current", announceTrackChange = false)
                active.stop(preserveTrackState = true)
                active.release(preserveTrackState = true)
                assertSame(verdict, AiDetectionManager.result.value)
            } finally {
                speculative.release(preserveTrackState = true)
                active.release(preserveTrackState = true)
                AiDetectionManager.resetResult()
            }
        }
    }

    @Test
    fun ordinaryTrackLoadingAndNextTrackActivationStillResetTheVerdict() {
        withDisabledAiRequests {
            val engine = AudioEngine()
            val verdict = AiDetectionManager.Result(0.94f, true, AiDetectionManager.Status.DONE)
            try {
                setVerdict(verdict)
                engine.setMediaItem("ordinary-current")
                assertNull(AiDetectionManager.result.value)
                engine.setMediaItem("speculative-next", announceTrackChange = false)
                setVerdict(verdict)
                engine.activateTrackPlayback()
                assertNull(AiDetectionManager.result.value)
                setVerdict(verdict)
                engine.stop()
                assertNull(AiDetectionManager.result.value)
            } finally {
                engine.release()
            }
        }
    }

    @Test
    fun sameTrackPromotionPreservesTheVerdictUntilTheActiveEngineStops() {
        withDisabledAiRequests {
            val engine = AudioEngine()
            val verdict = AiDetectionManager.Result(0.94f, true, AiDetectionManager.Status.DONE)
            try {
                engine.setMediaItem("same-track", announceTrackChange = false)
                setVerdict(verdict)
                engine.takeOverSameTrackPlayback()
                assertSame(verdict, AiDetectionManager.result.value)
                engine.stop()
                assertNull(AiDetectionManager.result.value)
            } finally {
                engine.release()
            }
        }
    }

    private fun withDisabledAiRequests(block: () -> Unit) {
        val prefs = PlayerPreferences()
        val badge = prefs.aiShowBadge
        val skip = prefs.aiAutoSkip
        try {
            prefs.aiShowBadge = false
            prefs.aiAutoSkip = false
            block()
        } finally {
            prefs.aiShowBadge = badge
            prefs.aiAutoSkip = skip
            AiDetectionManager.resetResult()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setVerdict(verdict: AiDetectionManager.Result) {
        val field = AiDetectionManager::class.java.getDeclaredField("_result").apply { isAccessible = true }
        (field.get(AiDetectionManager) as MutableStateFlow<AiDetectionManager.Result?>).value = verdict
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun line(written: MutableList<Byte>): SourceDataLine = Proxy.newProxyInstance(
        SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "write" -> {
                val bytes = args!![0] as ByteArray
                val offset = args[1] as Int
                val count = args[2] as Int
                written.addAll(bytes.slice(offset until offset + count))
                count
            }
            "equals" -> proxy === args!![0]
            "hashCode" -> System.identityHashCode(proxy)
            "isOpen", "isRunning", "isActive", "isControlSupported" -> false
            else -> null
        }
    } as SourceDataLine
}
