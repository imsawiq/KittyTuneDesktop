package com.alananasss.kittytune.data.lossless

import com.alananasss.kittytune.data.ResolvedStream
import com.alananasss.kittytune.data.download.LosslessDownloadPolicy
import com.alananasss.kittytune.domain.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import java.io.IOException

/** Progressive, bounded verification for the dedicated FLAC search; no background all-source fanout. */
internal class VerifiedLosslessSearch(
    private val resolve: suspend (Track) -> ResolvedStream? = { LosslessAudioRepository.resolve(it, allowLocal = false, verifyStream = false) },
    private val probe: suspend (String) -> Boolean = FlacStreamProbe()::verify,
    private val budgetMs: Long = 25_000L,
) {
    suspend fun verify(candidates: List<Track>, onVerified: suspend (Track) -> Unit) {
        val permits = Semaphore(3)
        withTimeoutOrNull(budgetMs) {
            coroutineScope {
                candidates.distinctBy { it.source to it.id }.take(18).map { track -> async {
                    permits.withPermit {
                        try {
                            val stream = resolve(track) ?: return@withPermit
                            if (stream.isDrmProtected || !LosslessDownloadPolicy.acceptsStream(stream.mimeType, stream.url)) return@withPermit
                            if (!probe(stream.url)) return@withPermit
                            coroutineContext.ensureActive()
                            onVerified(track.copy(requireLossless = true))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: IOException) {
                            // An unavailable candidate must not hide other verified results.
                        } catch (_: com.alananasss.kittytune.audio.providers.qobuz.QobuzAudioProvider.QobuzResolutionException) {
                            // Catalog presence is not proof that a FLAC stream is available.
                        }
                    }
                } }.awaitAll()
            }
        }
    }
}
