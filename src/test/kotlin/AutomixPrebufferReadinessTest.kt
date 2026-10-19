package com.alananasss.kittytune

import com.alananasss.kittytune.audio.AudioEngine
import com.alananasss.kittytune.audio.automix.AutomixPlan
import com.alananasss.kittytune.media.MediaItem
import com.alananasss.kittytune.media.Player
import com.alananasss.kittytune.media.isIncomingAudioReady
import org.junit.Test
import java.lang.reflect.Proxy
import javax.sound.sampled.SourceDataLine
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AutomixPrebufferReadinessTest {
    private val plan = AutomixPlan("1", "2", 80_000L, 12_000L, 1.08f, 0.97f, 8_000L)

    @Test
    fun lateBeatPlanRejectsAnAvailableUnplannedBuffer() {
        val player = Player()
        val prepared = installBuffer(player, null)
        try {
            assertTrue(player.isPrebuffered(2L))
            assertNull(player.takeCompatiblePrebuffer(2L, 0L, plan))
            assertFalse(player.isPrebuffered(2L))
            assertEquals(AudioEngine.State.IDLE, prepared.engine.state)
        } finally {
            player.release()
        }
    }

    @Test
    fun onlyTheIncomingPcmSettingsDetermineCompatibility() {
        val player = Player()
        val prepared = installBuffer(player, plan)
        try {
            val retimed = plan.copy(triggerTimeMs = 90_000L, overlapMs = 6_000L)
            assertSame(prepared, player.takeCompatiblePrebuffer(2L, 0L, retimed))
            assertFalse(player.isPrebuffered(2L))
            assertEquals(AudioEngine.State.BUFFERING, prepared.engine.state)
        } finally {
            prepared.engine.release()
            player.release()
        }
    }

    @Test
    fun changedStartTempoPitchOrTrackRequiresFreshPcm() {
        val engine = AudioEngine()
        try {
            val prepared = Player.PrebufferedTransition(engine, MediaItem.Builder().build(), 2L, plan, "test", emptyMap())
            assertFalse(prepared.matches(3L, 0L, plan))
            assertFalse(prepared.matches(2L, 0L, plan.copy(incomingStartMs = 13_000L)))
            assertFalse(prepared.matches(2L, 0L, plan.copy(tempoRatio = 1f)))
            assertFalse(prepared.matches(2L, 0L, plan.copy(pitchRatio = 1f)))
            assertFalse(prepared.matches(2L, 0L, null))
            assertTrue(prepared.matches(2L, 0L, plan))
        } finally {
            engine.release()
        }
    }

    @Test
    fun deviceReadyAndCompletedSeekStillNeedSuccessfulCurrentPcmWrite() {
        val engine = AudioEngine()
        var writtenBytes = 0
        val line = outputLine { bytes -> writtenBytes += bytes; bytes }
        setField(engine, "line", line)
        try {
            assertFalse(engine.hasBufferedAudio)
            assertFalse(isIncomingAudioReady(AudioEngine.State.READY, false, engine.hasBufferedAudio))
            engine.writeToLine(shortArrayOf(1, 2), 2, line)
            assertEquals(4, writtenBytes)
            assertTrue(engine.hasBufferedAudio)
            assertTrue(isIncomingAudioReady(AudioEngine.State.READY, false, engine.hasBufferedAudio))
            assertFalse(isIncomingAudioReady(AudioEngine.State.READY, true, engine.hasBufferedAudio))
            assertFalse(isIncomingAudioReady(AudioEngine.State.BUFFERING, false, engine.hasBufferedAudio))

            engine.seekTo(12_000L)
            assertFalse(engine.hasBufferedAudio)
            engine.writeToLine(shortArrayOf(1, 2), 2, line, revision = 0L)
            assertFalse(engine.hasBufferedAudio)
            engine.stop()
            assertFalse(engine.hasBufferedAudio)
        } finally {
            engine.release()
        }
    }

    @Test
    fun failedEmptyOrReplacedOutputCannotDeclareAudioReady() {
        val engine = AudioEngine()
        val line = outputLine { 0 }
        setField(engine, "line", line)
        try {
            engine.writeToLine(shortArrayOf(1, 2), 2, line)
            assertFalse(engine.hasBufferedAudio)
            val replaced = outputLine { it }
            engine.writeToLine(shortArrayOf(1, 2), 2, replaced)
            assertFalse(engine.hasBufferedAudio)
            val broken = outputLine { throw IllegalStateException("device disconnected") }
            setField(engine, "line", broken)
            engine.writeToLine(shortArrayOf(1, 2), 2, broken)
            assertFalse(engine.hasBufferedAudio)
        } finally {
            engine.release()
        }
    }

    @Test
    fun staleResolverFailureCannotStopOrChangeTheNewTransitionsEngines() {
        val player = Player()
        val outgoing = AudioEngine()
        val stale = AudioEngine()
        var failures = 0
        player.onError = { failures++ }
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: Throwable) { failures++ }
        })
        try {
            player.activeEngine.setMediaItem("current-incoming")
            outgoing.setMediaItem("current-outgoing")
            stale.setMediaItem("stale-transition")
            setField(player, "fadingEngine", outgoing)
            player.isCrossfadingOut = true
            player.setFadeGains(0.25f, 0.75f)

            val unavailable = IllegalStateException("Incoming stream unavailable")
            player.handleCrossfadeFailure(stale, outgoing, unavailable)
            player.handleCrossfadeFailure(player.activeEngine, stale, unavailable)

            assertEquals(0, failures)
            assertEquals(AudioEngine.State.BUFFERING, player.activeEngine.state)
            assertEquals(AudioEngine.State.BUFFERING, outgoing.state)
            assertEquals(0.25f, player.activeEngine.getVolume())
            assertEquals(0.75f, outgoing.getVolume())
            assertTrue(player.isCrossfadingOut)
        } finally {
            stale.release()
            player.release()
        }
    }

    private fun installBuffer(player: Player, plan: AutomixPlan?): Player.PrebufferedTransition {
        val engine = AudioEngine()
        engine.setMediaItem("test-prebuffer", startPositionMs = plan?.incomingStartMs ?: 0L)
        val prepared = Player.PrebufferedTransition(engine, MediaItem.Builder().build(), 2L, plan, "test", emptyMap())
        setField(player, "prebufferedTransition", prepared)
        return prepared
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun outputLine(write: (Int) -> Int): SourceDataLine = Proxy.newProxyInstance(
        SourceDataLine::class.java.classLoader,
        arrayOf(SourceDataLine::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "write" -> write(args!![2] as Int)
            "equals" -> proxy === args!![0]
            "hashCode" -> System.identityHashCode(proxy)
            "isOpen", "isRunning", "isActive", "isControlSupported" -> false
            else -> null
        }
    } as SourceDataLine
}
