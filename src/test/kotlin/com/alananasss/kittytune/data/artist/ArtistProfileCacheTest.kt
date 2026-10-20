package com.alananasss.kittytune.data.artist

import com.alananasss.kittytune.data.spotify.SpotifyArtist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistProfileCacheTest {
    private class Fixture(resolve: suspend (String) -> ArtistReach.Profile?) : AutoCloseable {
        var time = 0L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cache = ArtistProfileCache(scope, resolve) { time }
        fun cancelOwner() = scope.cancel()
        override fun close() = scope.cancel()
    }

    @Test
    fun `cancelled owners never leave unstarted requests occupying admission slots`() = runBlocking {
        var calls = 0
        Fixture { profile(++calls) }.use { f ->
            f.cancelOwner()
            var cancelled = 0
            repeat(70) { id ->
                try { f.cache.get("$id", "Artist $id") } catch (_: CancellationException) { cancelled++ }
            }
            assertEquals(70, cancelled)
            assertEquals(0, calls)
        }
    }

    @Test
    fun `a provider returning data after cancellation cannot poison the cache`() = runBlocking {
        var calls = 0
        Fixture {
            if (++calls == 1) currentCoroutineContext().cancel()
            profile(calls)
        }.use { f ->
            var cancelled = false
            try { f.cache.get("artist", "Artist") } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            assertEquals(2L, f.cache.get("artist", "Artist")?.monthlyListeners)
        }
    }

    @Test
    fun `instant lookups refresh after expiry instead of retaining a completed request`() = runBlocking {
        var calls = 0
        Fixture { profile(++calls) }.use { f ->
            assertEquals(1L, f.cache.get("artist", "Artist")?.monthlyListeners)
            assertEquals(1L, f.cache.get("artist", "Artist")?.monthlyListeners)
            f.time += TimeUnit.MINUTES.toNanos(30)
            assertEquals(2L, f.cache.get("artist", "Artist")?.monthlyListeners)
            assertEquals(2, calls)
        }
    }

    @Test
    fun `a missing profile retries after one minute`() = runBlocking {
        var calls = 0
        Fixture { if (++calls == 1) null else profile(20) }.use { f ->
            assertNull(f.cache.get("artist", "Artist"))
            f.time = TimeUnit.SECONDS.toNanos(59)
            assertNull(f.cache.get("artist", "Artist"))
            assertEquals(1, calls)
            f.time = TimeUnit.MINUTES.toNanos(1)
            assertEquals(20L, f.cache.get("artist", "Artist")?.monthlyListeners)
        }
    }

    @Test
    fun `cancelling a caller does not cancel a shared profile request`() = runBlocking {
        var calls = 0
        val result = CompletableDeferred<ArtistReach.Profile?>()
        Fixture { calls++; result.await() }.use { f ->
            val first = async(start = CoroutineStart.UNDISPATCHED) { f.cache.get("artist", "Artist") }
            val second = async(start = CoroutineStart.UNDISPATCHED) { f.cache.get("artist", "Artist") }
            first.cancelAndJoin()
            f.time = TimeUnit.HOURS.toNanos(1)
            result.complete(profile(30))
            assertEquals(30L, second.await()?.monthlyListeners)
            assertEquals(30L, f.cache.get("artist", "Artist")?.monthlyListeners)
            assertEquals(1, calls, "Lifetime must begin when the request completes, not when it starts")
        }
    }

    @Test
    fun `resolver cancellation clears the request without caching a miss`() = runBlocking {
        var calls = 0
        Fixture {
            if (++calls == 1) throw CancellationException("Own cancelled lookup")
            profile(40)
        }.use { f ->
            var cancelled = false
            try { f.cache.get("artist", "Artist") } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            assertEquals(40L, f.cache.get("artist", "Artist")?.monthlyListeners)
            assertEquals(2, calls)
        }
    }

    @Test
    fun `old profiles are evicted beyond the fixed cache capacity`() = runBlocking {
        var calls = 0
        Fixture { profile(++calls) }.use { f ->
            repeat(257) { f.cache.get("$it", "Artist $it") }
            f.cache.get("0", "Artist 0")
            assertEquals(258, calls)
        }
    }

    @Test
    fun `rapid scrolling bounds pending profiles and active requests`() = runBlocking {
        val result = CompletableDeferred<ArtistReach.Profile?>()
        var active = 0
        var maximumActive = 0
        Fixture {
            active++
            maximumActive = maxOf(maximumActive, active)
            try { result.await() } finally { active-- }
        }.use { f ->
            val pending = (0 until 70).map { id ->
                async(start = CoroutineStart.UNDISPATCHED) { f.cache.get("$id", "Artist $id") }
            }
            assertEquals(6, pending.count { it.isCompleted })
            result.complete(profile(50))
            assertEquals(64, pending.awaitAll().count { it != null })
            assertTrue(maximumActive <= 4)
        }
    }

    private fun profile(listeners: Int): ArtistReach.Profile {
        val artist = SpotifyArtist("own-id", "Own artist", monthlyListeners = listeners.toLong())
        return ArtistReach.Profile(
            spotifyId = artist.id, name = artist.name, monthlyListeners = artist.monthlyListeners,
            followers = null, avatarUrl = null, headerImageUrl = null, biography = null,
            isVerified = true, details = artist,
        )
    }
}
