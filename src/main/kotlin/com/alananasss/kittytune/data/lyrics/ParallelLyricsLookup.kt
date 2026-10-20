package com.alananasss.kittytune.data.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Concurrent providers may publish useful answers before their remaining requests finish. */
internal object ParallelLyricsLookup {
    suspend fun <T : Any> resolve(
        sources: List<suspend (suspend (T) -> Unit) -> T?>,
        timeoutMs: Long,
        ranking: Comparator<IndexedValue<T>>,
        onImproved: suspend (T) -> Unit = {},
    ): T? = coroutineScope {
        require(timeoutMs > 0) { "Lyrics source timeout must be positive" }
        if (sources.isEmpty()) return@coroutineScope null
        val events = Channel<Event<T>>(sources.size)
        val jobs = sources.mapIndexed { index, source ->
            launch(Dispatchers.IO) {
                try {
                    val result = withTimeoutOrNull(timeoutMs) {
                        source { events.send(Event.Answer(IndexedValue(index, it))) }
                    }
                    if (result != null) events.send(Event.Answer(IndexedValue(index, result)))
                } catch (cancelled: CancellationException) {
                    // A provider's explicit cancellation must not strand the receiver waiting for it.
                    currentCoroutineContext().ensureActive()
                    events.send(Event.Cancelled(cancelled))
                } catch (_: Exception) {
                    // A failed provider cannot invalidate useful answers from this or another source.
                }
                events.send(Event.Finished)
            }
        }
        var best: IndexedValue<T>? = null
        var finished = 0
        try {
            while (finished < sources.size) {
                when (val event = events.receive()) {
                    is Event.Answer -> {
                        val previous = best
                        val comparison = previous?.let { ranking.compare(event.answer, it) } ?: 1
                        // The same source may enrich its timings with performer metadata later.
                        val refreshed = previous != null && comparison == 0 &&
                            event.answer.index == previous.index && event.answer.value != previous.value
                        if (comparison > 0 || refreshed) {
                            best = event.answer
                            onImproved(event.answer.value)
                        }
                    }
                    is Event.Cancelled -> throw event.cause
                    Event.Finished -> finished++
                }
            }
            best?.value
        } finally {
            jobs.forEach { it.cancel() }
            events.cancel()
        }
    }

    private sealed interface Event<out T> {
        data class Answer<T>(val answer: IndexedValue<T>) : Event<T>
        data class Cancelled(val cause: CancellationException) : Event<Nothing>
        data object Finished : Event<Nothing>
    }
}
