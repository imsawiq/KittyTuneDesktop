package com.alananasss.kittytune.data.lossless

import com.alananasss.kittytune.data.ResolvedStream
import com.alananasss.kittytune.domain.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifiedLosslessSearchTest {
    @Test fun `only header verified plain FLAC is published with strict quality preserved`() = runBlocking {
        val verified = mutableListOf<Track>()
        val search = VerifiedLosslessSearch(resolve = {
            when (it.id) {
                1L -> stream("audio/flac", "good")
                2L -> stream("audio/mpeg", "mp3")
                3L -> stream("audio/flac", "fake")
                4L -> stream("audio/flac", "playlist.m3u8")
                else -> ResolvedStream("https://example.test/drm", "license", "audio/flac")
            }
        }, probe = { it.endsWith("/good") })
        search.verify((1L..5L).map(::track)) { verified += it }
        assertEquals(listOf(1L), verified.map { it.id })
        assertTrue(verified.single().requireLossless)
        assertFalse(track(1).requireLossless)
    }

    @Test fun `a fast verified result is published before a slow candidate completes`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val published = CompletableDeferred<Unit>()
        val search = VerifiedLosslessSearch(resolve = {
            if (it.id == 1L) release.await()
            stream("audio/flac", it.id.toString())
        }, probe = { true })
        val job = launch { search.verify(listOf(track(1), track(2))) { if (it.id == 2L) published.complete(Unit) } }
        try {
            withTimeout(2_000) { published.await() }
            assertFalse(job.isCompleted)
        } finally { release.complete(Unit); job.cancelAndJoin() }
    }

    @Test fun `verification deduplicates caps candidates and limits concurrent resolution`() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val resolved = AtomicInteger()
        val search = VerifiedLosslessSearch(resolve = {
            resolved.incrementAndGet()
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try { delay(5); stream("audio/flac", it.id.toString()) }
            finally { active.decrementAndGet() }
        }, probe = { true })
        val results = mutableListOf<Track>()
        search.verify((1L..40L).flatMap { listOf(track(it), track(it)) }) { results += it }
        assertEquals(18, resolved.get())
        assertEquals(18, results.map { it.id }.distinct().size)
        assertTrue(peak.get() in 1..3)
    }

    @Test fun `cancel and deadline release pending verification without publishing stale results`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = AtomicInteger()
        val search = VerifiedLosslessSearch(resolve = {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.incrementAndGet() }
        }, probe = { true })
        val job = launch { search.verify(listOf(track(1))) { error("Cancelled result was published") } }
        withTimeout(2_000) { started.await() }
        job.cancelAndJoin()
        assertEquals(1, cancelled.get())
        VerifiedLosslessSearch(resolve = { awaitCancellation() }, probe = { true }, budgetMs = 25)
            .verify(listOf(track(2))) { error("Expired result was published") }
    }

    private fun track(id: Long) = Track(id, "Track$id", null, 180_000, null, source = "qobuz")
    private fun stream(mime: String, name: String) = ResolvedStream("https://example.test/$name", mimeType = mime)
}
