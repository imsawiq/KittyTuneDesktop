package com.alananasss.kittytune.data.lyrics

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParallelLyricsLookupTest {
    private data class Answer(val name: String, val confident: Boolean = true, val tier: Int = 2, val rank: Int = 1)

    private val ranking = compareBy<IndexedValue<Answer>> { if (it.value.confident) 1 else 0 }
        .thenBy { it.value.tier }
        .thenBy { it.value.rank }
        .thenByDescending { it.index }

    @Test fun `all sources start and quick synced lyrics publish before slow word timings`() = runBlocking {
        val started = AtomicInteger()
        val allStarted = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        val firstPublished = CompletableDeferred<Answer>()
        val published = mutableListOf<Answer>()
        suspend fun start() {
            if (started.incrementAndGet() == 2) allStarted.complete(Unit)
            allStarted.await()
        }
        val lookup = async {
            ParallelLyricsLookup.resolve(listOf(
                source { start(); Answer("quick lines") },
                source { start(); releaseSlow.await(); Answer("later words", tier = 3) },
            ), 5_000, ranking) {
                published.add(it)
                firstPublished.complete(it)
            }
        }
        try {
            assertEquals("quick lines", withTimeout(2_000) { firstPublished.await() }.name)
            assertEquals(2, started.get())
            assertFalse(lookup.isCompleted)
            releaseSlow.complete(Unit)
            assertEquals("later words", withTimeout(2_000) { lookup.await() }?.name)
            assertEquals(listOf("quick lines", "later words"), published.map { it.name })
        } finally {
            lookup.cancelAndJoin()
        }
    }

    @Test fun `partial synced answer survives its own source timing out`() = runBlocking {
        val firstPublished = CompletableDeferred<Answer>()
        val lookup = async {
            ParallelLyricsLookup.resolve(listOf(source { publish ->
                publish(Answer("structured search"))
                awaitCancellation()
            }), 250, ranking) { firstPublished.complete(it) }
        }
        assertEquals("structured search", withTimeout(2_000) { firstPublished.await() }.name)
        assertEquals("structured search", withTimeout(2_000) { lookup.await() }?.name)
    }

    @Test fun `unresponsive provider does not discard another providers quick result`() = runBlocking {
        val result = withTimeout(2_000) {
            ParallelLyricsLookup.resolve(listOf(
                source { Answer("quick lines") },
                source { awaitCancellation() },
            ), 250, ranking)
        }
        assertEquals("quick lines", result?.name)
    }

    @Test fun `failed followup does not erase its published partial answer`() = runBlocking {
        val result = ParallelLyricsLookup.resolve(listOf(source { publish ->
            publish(Answer("cached endpoint"))
            throw IOException("Second endpoint unavailable")
        }), 2_000, ranking)
        assertEquals("cached endpoint", result?.name)
    }

    @Test fun `confidence timings rank and configured provider order determine final winner`() = runBlocking {
        val first = Answer("preferred provider", tier = 3, rank = 10)
        val result = ParallelLyricsLookup.resolve(listOf(
            source { first },
            source { Answer("same quality later provider", tier = 3, rank = 10) },
            source { Answer("wrong song richer rank", confident = false, tier = 3, rank = 100) },
            source { Answer("plain text higher rank", tier = 1, rank = 100) },
            source { Answer("word timings lower rank", tier = 3, rank = 9) },
        ), 2_000, ranking)
        assertEquals(first, result)
    }

    @Test fun `same provider can enrich metadata without changing quality or provider ordering`() = runBlocking {
        val published = mutableListOf<String>()
        val result = ParallelLyricsLookup.resolve(listOf(source { publish ->
            publish(Answer("line timings"))
            Answer("line timings with performers")
        }), 2_000, ranking) { published.add(it.name) }
        assertEquals("line timings with performers", result?.name)
        assertEquals(listOf("line timings", "line timings with performers"), published)
    }

    @Test fun `explicit provider cancellation propagates without hanging collector`() = runBlocking {
        val failure = assertFailsWith<CancellationException> {
            withTimeout(2_000) {
                ParallelLyricsLookup.resolve(listOf(source {
                    throw CancellationException("Track changed")
                }), 5_000, ranking)
            }
        }
        assertEquals("Track changed", failure.message)
    }

    @Test fun `caller cancellation stops every provider`() = runBlocking {
        val started = AtomicInteger()
        val stopped = AtomicInteger()
        val allStarted = CompletableDeferred<Unit>()
        val blocking = source {
            try {
                if (started.incrementAndGet() == 2) allStarted.complete(Unit)
                awaitCancellation()
            } finally {
                stopped.incrementAndGet()
            }
        }
        val lookup = async { ParallelLyricsLookup.resolve(listOf(blocking, blocking), 10_000, ranking) }
        withTimeout(2_000) { allStarted.await() }
        withTimeout(2_000) { lookup.cancelAndJoin() }
        assertTrue(lookup.isCancelled)
        assertEquals(2, stopped.get())
    }

    private fun source(request: suspend (suspend (Answer) -> Unit) -> Answer?) = request
}
