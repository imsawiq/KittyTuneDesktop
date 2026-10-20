package com.alananasss.kittytune.data.artist

import com.alananasss.kittytune.core.BoundedCache
import com.alananasss.kittytune.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit

/** Fresh artist data with shared requests, short retries for misses, and bounded background work. */
internal class ArtistProfileCache(
    private val scope: CoroutineScope,
    private val resolve: suspend (String) -> ArtistReach.Profile?,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private data class Lookup(val profile: ArtistReach.Profile?, val completedAt: Long, val lifetime: Long)

    private val lock = Any()
    private val cache = BoundedCache<String, Lookup>(256)
    private val inFlight = mutableMapOf<String, Deferred<ArtistReach.Profile?>>()
    private val requests = Semaphore(4)

    suspend fun get(key: String, name: String): ArtistReach.Profile? {
        val request = synchronized(lock) {
            cache[key]?.let { cached ->
                if (nowNanos() - cached.completedAt in 0L until cached.lifetime) return cached.profile
            }
            inFlight[key] ?: run {
                // A fast-scrolled catalogue must not queue an unbounded number of artist requests.
                if (inFlight.size >= 64) return null
                scope.async(start = CoroutineStart.LAZY) {
                    val profile = try {
                        requests.withPermit { resolve(name) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Logger.w("ArtistProfileCache", "Artist profile lookup failed", failure)
                        null
                    }
                    // A provider may catch cancellation internally and return a fallback value.
                    coroutineContext.ensureActive()
                    val lifetime = if (profile == null) MISS_LIFETIME else PROFILE_LIFETIME
                    synchronized(lock) { cache[key] = Lookup(profile, nowNanos(), lifetime) }
                    profile
                }.also { task ->
                    inFlight[key] = task
                    // Completion also runs when a lazy request is cancelled before its body starts.
                    task.invokeOnCompletion {
                        synchronized(lock) {
                            if (inFlight[key] === task) inFlight.remove(key)
                        }
                    }
                }
            }
        }
        // Publish before starting: an immediate result must not leave a completed Deferred in the map.
        request.start()
        return request.await()
    }

    private companion object {
        val PROFILE_LIFETIME = TimeUnit.MINUTES.toNanos(30)
        val MISS_LIFETIME = TimeUnit.MINUTES.toNanos(1)
    }
}
