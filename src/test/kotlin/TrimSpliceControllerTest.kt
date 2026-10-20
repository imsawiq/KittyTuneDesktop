package com.alananasss.kittytune

import com.alananasss.kittytune.audio.TrackTrim
import com.alananasss.kittytune.audio.AudioEngine
import com.alananasss.kittytune.audio.TrimInteriorJump
import com.alananasss.kittytune.audio.TrimMode
import com.alananasss.kittytune.audio.TrimSegment
import com.alananasss.kittytune.media.TrimSpliceController
import com.alananasss.kittytune.media.TrimSpliceDeck
import com.alananasss.kittytune.media.TrimSpliceHost
import com.alananasss.kittytune.media.TrimSpliceResult
import com.alananasss.kittytune.media.MediaItem
import com.alananasss.kittytune.media.Player
import com.alananasss.kittytune.ui.player.AudioEffectsState
import com.alananasss.kittytune.media.trimSpliceGains
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TrimSpliceControllerTest {
    private val jump = TrimInteriorJump(10_000L, 20_000L)

    @Test
    fun onlyInteriorCutAndKeepBoundariesArePrepared() {
        val cut = TrackTrim.of(TrimMode.CUT, listOf(TrimSegment(0, 1_000), TrimSegment(10_000, 15_000),
            TrimSegment(15_000, 20_000), TrimSegment(25_000, 30_000)))
        assertEquals(jump, cut.nextInteriorJump(1_000L, 30_000L))
        assertNull(cut.nextInteriorJump(20_000L, 30_000L))
        assertNull(cut.nextInteriorJump(0L, 0L))
        val keep = TrackTrim.of(TrimMode.KEEP, listOf(TrimSegment(1_000, 10_000), TrimSegment(20_000, 30_000)))
        assertEquals(jump, keep.nextInteriorJump(2_000L, 40_000L))
        assertNull(keep.nextInteriorJump(20_000L, 40_000L))
    }

    @Test
    fun aPreparedSpliceCommitsOnceAndTransfersDecoderOwnership() = runBlocking {
        val host = Host()
        withController(host) { controller ->
            assertTrue(controller.prepare(jump))
            assertTrue(controller.prepare(jump))
            assertEquals(1, host.created)
            assertEquals(TrimSpliceResult.COMPLETED, controller.splice(jump))
            assertEquals(1, host.commits)
            assertFalse(controller.isReserved)
            assertEquals(0, host.deck.releases)
            assertSame(host.deck.identity, host.currentIdentity)
            assertEquals(0f, host.gains.last().first)
            assertEquals(1f, host.gains.last().second)
        }
    }

    @Test
    fun playerSameTrackCommitKeepsTheItemAndEmitsOnlyOneSeekDiscontinuity() {
        val player = Player()
        val incoming = AudioEngine()
        val item = MediaItem.Builder().setMediaId("same-track").build()
        var transitions = 0
        var seeks = 0
        player.addMediaItem(item)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { transitions++ }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                seeks++
            }
        })
        try {
            player.activeEngine.setMediaItem("outgoing", announceTrackChange = false)
            incoming.setMediaItem("incoming", announceTrackChange = false)
            setField(player, "playWhenReady", true)
            val deckClass = Player::class.java.declaredClasses.single { it.simpleName == "EngineTrimDeck" }
            val deck = deckClass.getDeclaredConstructor(AudioEngine::class.java).apply { isAccessible = true }
                .newInstance(incoming) as TrimSpliceDeck
            setField(player, "trimSpliceDeck", deck)
            val controller = field(player, "trimSpliceController") as TrimSpliceController
            val host = field(controller, "host") as TrimSpliceHost

            assertTrue(host.commit(player.activeEngine, deck))
            assertSame(incoming, player.activeEngine)
            assertSame(item, player.currentMediaItem)
            assertEquals(0, player.currentMediaItemIndex)
            assertEquals(0, transitions)
            assertEquals(1, seeks)
        } finally {
            player.release()
            if (player.activeEngine !== incoming) incoming.release(preserveTrackState = true)
        }
    }

    @Test
    fun playerLifecycleActionsReleaseSpeculativeHeadsBeforeChangingPlayback() {
        val actions = listOf<Pair<String, (Player) -> Unit>>(
            "pause" to { it.pause() },
            "seek" to { it.seekTo(5_000L) },
            "stop" to { it.stop() },
            "release" to { it.release() },
            "trim edit" to { it.cancelTrimSplice() },
            "new media" to { it.setMediaItemUrl("new-current", emptyMap()) },
            "effects" to { it.applyEffects(AudioEffectsState()) },
        )
        for ((name, action) in actions) {
            val player = Player()
            val incoming = AudioEngine()
            try {
                incoming.setMediaItem("prepared-head", announceTrackChange = false)
                val deckClass = Player::class.java.declaredClasses.single { it.simpleName == "EngineTrimDeck" }
                val deck = deckClass.getDeclaredConstructor(AudioEngine::class.java).apply { isAccessible = true }
                    .newInstance(incoming) as TrimSpliceDeck
                setField(player, "trimSpliceDeck", deck)
                val controller = field(player, "trimSpliceController") as TrimSpliceController
                val preparationClass = TrimSpliceController::class.java.declaredClasses.single { it.simpleName == "Preparation" }
                val preparation = preparationClass.getDeclaredConstructor(Any::class.java, TrimInteriorJump::class.java)
                    .apply { isAccessible = true }.newInstance(player.activeEngine, jump)
                setField(preparation, "deck", deck)
                setField(controller, "preparation", preparation)

                action(player)

                assertEquals(AudioEngine.State.IDLE, incoming.state, name)
                assertFalse(controller.isReserved, name)
                assertNull(field(player, "trimSpliceDeck"), name)
            } finally {
                incoming.release(preserveTrackState = true)
                player.release()
            }
        }
    }

    @Test
    fun anExistingNextTrackReservationKeepsItsSecondarySlot() = runBlocking {
        val host = Host().apply { nextTrackReserved = true }
        withController(host) { controller ->
            assertFalse(controller.prepare(jump))
            assertEquals(0, host.created)
            assertTrue(host.nextTrackReserved)
            assertFalse(controller.isReserved)
        }
    }

    @Test
    fun readyWithoutPrimedPcmFallsBackAndReleasesExactlyOnce() = runBlocking {
        val host = Host().apply { deck.isPrimed = false }
        withController(host) { controller ->
            assertTrue(controller.prepare(jump))
            assertFalse(controller.isReady(jump))
            assertEquals(TrimSpliceResult.UNAVAILABLE, controller.splice(jump))
            controller.cancel()
            assertEquals(1, host.deck.releases)
            assertEquals(0, host.commits)
            assertEquals(1f to 0f, host.gains.last())
        }
    }

    @Test
    fun aStalledOutputFallsBackWithinTheBoundedReadinessWindow() = runBlocking {
        val host = Host().apply { deck.bufferOnPlay = false }
        var elapsed = 0L
        withController(host, { elapsed += it }) { controller ->
            controller.prepare(jump)
            assertEquals(TrimSpliceResult.UNAVAILABLE, controller.splice(jump))
            assertEquals(150L, elapsed)
            assertEquals(0, host.commits)
            assertEquals(1, host.deck.releases)
            assertEquals(1f to 0f, host.gains.last())
        }
    }

    @Test
    fun aNewSeekCancelsAnOldRampWithoutChangingTheNewOwnersGains() = runBlocking {
        val host = Host()
        lateinit var controller: TrimSpliceController
        var cancelled = false
        withController(host, {
            if (!cancelled) {
                cancelled = true
                host.currentIdentity = Any()
                host.gains += 0.6f to 0f
                controller.cancel()
            }
        }) {
            controller = it
            controller.prepare(jump)
            assertEquals(TrimSpliceResult.CANCELLED, controller.splice(jump))
            assertEquals(0.6f to 0f, host.gains.last())
            assertEquals(0, host.commits)
            assertEquals(1, host.deck.releases)
        }
    }

    @Test
    fun syntheticPcmOverlapHasNoInsertedSilenceAndEqualPowerGains() {
        val count = 4_410
        val outgoing = FloatArray(count) { sin(2.0 * PI * 440 * it / 44_100).toFloat() * 0.25f }
        val incoming = FloatArray(count) { sin(2.0 * PI * 733 * it / 44_100 + PI / 3).toFloat() * 0.25f }
        val mixed = FloatArray(count) { frame ->
            val (outGain, inGain) = trimSpliceGains(frame.toFloat() / (count - 1))
            assertTrue(abs(outGain * outGain + inGain * inGain - 1f) < 0.00001f)
            outgoing[frame] * outGain + incoming[frame] * inGain
        }
        assertEquals(outgoing.first(), mixed.first(), 0.00001f)
        assertEquals(incoming.last(), mixed.last(), 0.00001f)
        assertTrue(mixed.all { it.isFinite() && abs(it) <= 0.36f })
        assertTrue(mixed.toList().chunked(64).all { block -> block.any { abs(it) > 0.001f } })
    }

    private suspend fun withController(
        host: Host,
        tick: suspend (Long) -> Unit = {},
        block: suspend (TrimSpliceController) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = TrimSpliceController(scope, host) { ms ->
            if (ms == 4_000L) awaitCancellation() else tick(ms)
        }
        try { block(controller) } finally { controller.cancel(); scope.cancel() }
    }

    private class Deck : TrimSpliceDeck {
        override val identity: Any = Any()
        override var isPrimed = true
        override var isBuffered = false
        override var hasFailed = false
        override val positionMs = 20_050L
        var bufferOnPlay = true
        var releases = 0
        override fun play() { if (bufferOnPlay) isBuffered = true }
        override fun release() { releases++ }
    }

    private class Host : TrimSpliceHost {
        override var currentIdentity: Any = Any()
        var nextTrackReserved = false
        override val canPrepare: Boolean get() = !nextTrackReserved
        val deck = Deck()
        var created = 0
        var commits = 0
        val gains = mutableListOf<Pair<Float, Float>>()
        override fun createIncoming(jump: TrimInteriorJump): TrimSpliceDeck { created++; return deck }
        override fun canContinue(outgoing: Any, incoming: TrimSpliceDeck) = currentIdentity === outgoing
        override fun setGains(outgoing: Any, incoming: TrimSpliceDeck, outgoingGain: Float, incomingGain: Float): Boolean {
            if (!canContinue(outgoing, incoming)) return false
            gains += outgoingGain to incomingGain
            return true
        }
        override fun commit(outgoing: Any, incoming: TrimSpliceDeck): Boolean {
            if (!canContinue(outgoing, incoming)) return false
            commits++; currentIdentity = incoming.identity
            return true
        }
        override fun restore(outgoing: Any, incoming: TrimSpliceDeck) {
            if (currentIdentity === outgoing) gains += 1f to 0f
        }
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
