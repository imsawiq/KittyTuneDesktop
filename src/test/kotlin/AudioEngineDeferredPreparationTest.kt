package com.alananasss.kittytune

import com.alananasss.kittytune.audio.AudioEngine
import com.alananasss.kittytune.audio.ai.AiDetectionManager
import com.alananasss.kittytune.data.local.PlayerPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AudioEngineDeferredPreparationTest {
    @Test
    fun pausedRestoreAndSeekKeepMetadataWithoutStartingADecoder() {
        val engine = AudioEngine()
        try {
            engine.setMediaItem("offline-restored-track", startPositionMs = 12_000L,
                deferPreparation = true, knownDurationMs = 180_000L)
            repeat(3) { engine.prepare() }
            engine.seekTo(24_000L)
            engine.prepare()
            assertEquals(24_000L, engine.positionMs)
            assertEquals(180_000L, engine.durationMs)
            assertEquals(AudioEngine.State.IDLE, engine.state)
            assertTrue(engine.isPreparationDeferred)
            assertFalse(engine.isPlaying)
            assertNull(field(engine, "worker"))
            assertNull(field(engine, "line"))
            engine.stop()
            assertFalse(engine.isPreparationDeferred)
            assertNull(engine.currentUrl)
        } finally {
            engine.release()
        }
    }

    @Test
    fun firstActivationResetsVerdictOnceAndRepeatedActivationPreservesIt() {
        val prefs = PlayerPreferences()
        val badge = prefs.aiShowBadge
        val skip = prefs.aiAutoSkip
        val engine = AudioEngine()
        try {
            prefs.aiShowBadge = false
            prefs.aiAutoSkip = false
            val verdict = AiDetectionManager.Result(0.94f, true, AiDetectionManager.Status.DONE)
            setVerdict(verdict)
            engine.setMediaItem("offline-restored-track", deferPreparation = true)
            assertSame(verdict, AiDetectionManager.result.value)
            engine.activateDeferredPreparation()
            assertFalse(engine.isPreparationDeferred)
            assertNull(AiDetectionManager.result.value)
            setVerdict(verdict)
            engine.activateDeferredPreparation()
            engine.activateTrackPlayback()
            assertSame(verdict, AiDetectionManager.result.value)
            assertNull(field(engine, "worker"))
        } finally {
            engine.release()
            prefs.aiShowBadge = badge
            prefs.aiAutoSkip = skip
            AiDetectionManager.resetResult()
        }
    }

    @Test
    fun prepareDoesNotReplaceAnExistingWorkerEvenBeforeReadyIsPublished() {
        val engine = AudioEngine()
        val finish = CountDownLatch(1)
        val worker = thread(isDaemon = true) { finish.await() }
        try {
            engine.setMediaItem("offline-restored-track", announceTrackChange = false, deferPreparation = true)
            engine.activateDeferredPreparation()
            engine.javaClass.getDeclaredField("worker").apply { isAccessible = true }.set(engine, worker)
            engine.prepare()
            assertSame(worker, field(engine, "worker"))
        } finally {
            finish.countDown()
            worker.join(1_000L)
            engine.release()
        }
    }

    private fun field(engine: AudioEngine, name: String): Any? =
        engine.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(engine)

    @Suppress("UNCHECKED_CAST")
    private fun setVerdict(value: AiDetectionManager.Result) {
        val result = AiDetectionManager::class.java.getDeclaredField("_result").apply { isAccessible = true }
        (result.get(AiDetectionManager) as MutableStateFlow<AiDetectionManager.Result?>).value = value
    }
}
