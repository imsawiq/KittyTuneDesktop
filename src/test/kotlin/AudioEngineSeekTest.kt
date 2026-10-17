package com.alananasss.kittytune

import com.alananasss.kittytune.audio.AudioEngine
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioEngineSeekTest {
    @Test
    fun latestSeekOwnsReportedPositionBeforeDecoderResumes() {
        val engine = AudioEngine()
        try {
            engine.seekTo(190_000L)
            engine.seekTo(42_000L)
            assertEquals(42_000L, engine.positionMs)
            assertTrue(engine.isSeeking)
        } finally {
            engine.release()
        }
    }

    @Test
    fun stopClearsPendingSeekAndNegativeTargetsClampToZero() {
        val engine = AudioEngine()
        try {
            engine.seekTo(-300L)
            assertEquals(0L, engine.positionMs)
            assertTrue(engine.isSeeking)
            engine.stop()
            assertFalse(engine.isSeeking)
        } finally {
            engine.release()
        }
    }
}
