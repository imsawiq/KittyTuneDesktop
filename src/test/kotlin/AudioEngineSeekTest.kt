package com.alananasss.kittytune

import com.alananasss.kittytune.audio.AudioEngine
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
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
    @Test
    fun aDecodedFrameFinishingAfterSeekCannotPublishTheOldEndPosition() {
        val engine = AudioEngine()
        val decoded = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val published = AtomicBoolean(true)
        val worker = thread(start = true) {
            // The worker captured revision zero before reading a frame near the old end.
            decoded.countDown()
            if (resume.await(5, TimeUnit.SECONDS)) {
                published.set(engine.publishDecodedPosition(190_000L, 0L))
            }
        }
        try {
            assertTrue(decoded.await(5, TimeUnit.SECONDS))
            engine.seekTo(42_000L)
            resume.countDown()
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertFalse(published.get())
            assertEquals(42_000L, engine.positionMs)
        } finally {
            resume.countDown()
            worker.join(5_000)
            engine.release()
        }
    }

    @Test
    fun aStaleRevisionCannotPublishWhenThereIsNoPendingSeekAnymore() {
        val engine = AudioEngine()
        try {
            assertTrue(engine.publishDecodedPosition(190_000L, 0L))
            engine.seekTo(42_000L)
            engine.stop()
            assertFalse(engine.isSeeking)
            assertFalse(engine.publishDecodedPosition(190_000L, 0L))
            assertEquals(0L, engine.positionMs)
        } finally {
            engine.release()
        }
    }

}
