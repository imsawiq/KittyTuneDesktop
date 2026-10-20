package com.alananasss.kittytune.media

import com.alananasss.kittytune.audio.TrimInteriorJump
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

internal interface TrimSpliceDeck {
    val identity: Any
    val isPrimed: Boolean
    val isBuffered: Boolean
    val hasFailed: Boolean
    val positionMs: Long
    fun play()
    fun release()
}

internal interface TrimSpliceHost {
    val currentIdentity: Any
    val canPrepare: Boolean
    fun createIncoming(jump: TrimInteriorJump): TrimSpliceDeck?
    fun canContinue(outgoing: Any, incoming: TrimSpliceDeck): Boolean
    fun setGains(outgoing: Any, incoming: TrimSpliceDeck, outgoingGain: Float, incomingGain: Float): Boolean
    fun commit(outgoing: Any, incoming: TrimSpliceDeck): Boolean
    fun restore(outgoing: Any, incoming: TrimSpliceDeck)
}

internal enum class TrimSpliceResult { COMPLETED, UNAVAILABLE, CANCELLED }

internal fun trimSpliceGains(progress: Float): Pair<Float, Float> {
    val angle = progress.coerceIn(0f, 1f) * Math.PI.toFloat() / 2f
    return cos(angle).coerceIn(0f, 1f) to sin(angle).coerceIn(0f, 1f)
}

/** One speculative same-track decoder, with no queue, track metadata, or listening-session ownership. */
internal class TrimSpliceController(
    private val scope: CoroutineScope,
    private val host: TrimSpliceHost,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    companion object {
        const val OVERLAP_MS = 80L
        const val LOOKAHEAD_MS = 3_000L
        private const val TTL_MS = 4_000L
    }

    private class Preparation(val outgoing: Any, val jump: TrimInteriorJump) {
        @Volatile var deck: TrimSpliceDeck? = null
        var expiry: Job? = null
    }

    @Volatile private var preparation: Preparation? = null
    val isReserved: Boolean get() = preparation != null

    fun prepare(jump: TrimInteriorJump): Boolean {
        val existing = preparation
        if (existing?.jump == jump && existing.outgoing === host.currentIdentity) return true
        cancel()
        if (!host.canPrepare) return false
        val pending = Preparation(host.currentIdentity, jump)
        synchronized(this) { preparation = pending }
        val deck = try { host.createIncoming(jump) } catch (_: Exception) { null }
        val retained = synchronized(this) {
            if (preparation !== pending || deck == null) false else {
                pending.deck = deck
                true
            }
        }
        if (!retained) {
            deck?.let { host.restore(pending.outgoing, it); it.release() }
            synchronized(this) { if (preparation === pending) preparation = null }
            return false
        }
        pending.expiry = scope.launch {
            wait(TTL_MS)
            if (preparation === pending) cancel()
        }
        return true
    }

    fun isReady(jump: TrimInteriorJump): Boolean {
        val pending = preparation ?: return false
        val deck = pending.deck ?: return false
        return pending.jump == jump && pending.outgoing === host.currentIdentity && deck.isPrimed && !deck.hasFailed
    }

    suspend fun splice(jump: TrimInteriorJump): TrimSpliceResult {
        val pending = preparation ?: return TrimSpliceResult.UNAVAILABLE
        val incoming = pending.deck ?: return TrimSpliceResult.UNAVAILABLE
        if (pending.jump != jump || !isReady(jump) || !host.canContinue(pending.outgoing, incoming)) {
            discard(pending)
            return TrimSpliceResult.UNAVAILABLE
        }
        pending.expiry?.cancel()
        var committed = false
        try {
            incoming.play()
            var waited = 0L
            while (!incoming.isBuffered && waited < 150L) {
                if (preparation !== pending || !host.canContinue(pending.outgoing, incoming)) return TrimSpliceResult.CANCELLED
                if (incoming.hasFailed) return TrimSpliceResult.UNAVAILABLE
                wait(5L)
                waited += 5L
            }
            if (!incoming.isBuffered) return TrimSpliceResult.UNAVAILABLE
            for (step in 0..8) {
                if (!currentCoroutineContext().isActive || preparation !== pending) return TrimSpliceResult.CANCELLED
                if (!host.canContinue(pending.outgoing, incoming)) return TrimSpliceResult.CANCELLED
                if (incoming.hasFailed) return TrimSpliceResult.UNAVAILABLE
                val (outGain, inGain) = trimSpliceGains(step / 8f)
                if (!host.setGains(pending.outgoing, incoming, outGain, inGain)) return TrimSpliceResult.CANCELLED
                if (step < 8) wait(OVERLAP_MS / 8L)
            }
            if (preparation !== pending) return TrimSpliceResult.CANCELLED
            committed = host.commit(pending.outgoing, incoming)
            return if (committed) TrimSpliceResult.COMPLETED else TrimSpliceResult.CANCELLED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return TrimSpliceResult.UNAVAILABLE
        } finally {
            if (committed) synchronized(this) { if (preparation === pending) preparation = null }
            else discard(pending)
        }
    }

    fun cancel() {
        val pending = synchronized(this) { preparation.also { preparation = null } } ?: return
        dispose(pending)
    }

    private fun discard(pending: Preparation) {
        val owned = synchronized(this) {
            if (preparation !== pending) false else { preparation = null; true }
        }
        if (owned) dispose(pending)
    }

    private fun dispose(pending: Preparation) {
        pending.expiry?.cancel()
        pending.deck?.let {
            if (host.currentIdentity !== it.identity) {
                host.restore(pending.outgoing, it)
                it.release()
            }
        }
    }
}
