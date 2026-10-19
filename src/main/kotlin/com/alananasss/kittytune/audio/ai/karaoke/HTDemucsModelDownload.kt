package com.alananasss.kittytune.audio.ai.karaoke

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** Tries independent hosts, accepting only the same pinned model bytes from either one. */
internal class HTDemucsModelDownload(
    private val client: OkHttpClient,
    private val urls: List<String> = listOf(HTDemucsModelIntegrity.URL, HTDemucsModelIntegrity.MIRROR_URL),
    private val expectedSize: Long = HTDemucsModelIntegrity.SIZE,
    private val expectedSha256: String = HTDemucsModelIntegrity.SHA256,
) {
    suspend fun download(
        temporary: File,
        activeCall: AtomicReference<Call?>,
        progress: (Long) -> Unit,
    ) {
        require(urls.isNotEmpty()) { "No HTDemucs download source is available" }
        var lastFailure: Exception? = null
        for ((index, url) in urls.withIndex()) {
            currentCoroutineContext().ensureActive()
            // A blocked first CDN must not consume the entire fifteen-minute download window.
            val attemptClient = client.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(if (index == 0) 90 else 900, TimeUnit.SECONDS)
                .followRedirects(true).followSslRedirects(true).build()
            val call = attemptClient.newCall(Request.Builder().url(url)
                .header("User-Agent", "KittyTuneDesktop").build())
            activeCall.set(call)
            try {
                currentCoroutineContext().ensureActive()
                progress(0L)
                withCancellation(call) {
                    call.execute().use { response ->
                        require(response.isSuccessful) { "HTTP ${response.code} downloading HTDemucs model" }
                        val body = response.body
                        require(body.contentLength().let { it < 0L || it == expectedSize }) { "Unexpected HTDemucs model size" }
                        var received = 0L
                        temporary.outputStream().buffered().use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    received += count
                                    require(received <= expectedSize) { "HTDemucs model exceeds expected size" }
                                    output.write(buffer, 0, count)
                                    progress(received)
                                }
                            }
                        }
                    }
                }
                require(temporary.length() == expectedSize) { "HTDemucs model download is incomplete" }
                require(HTDemucsModelIntegrity.sha256(temporary) == expectedSha256) { "HTDemucs model checksum does not match" }
                currentCoroutineContext().ensureActive()
                return
            } catch (cancelled: CancellationException) {
                temporary.delete()
                throw cancelled
            } catch (failure: Exception) {
                temporary.delete()
                currentCoroutineContext().ensureActive()
                lastFailure = failure
            } finally {
                activeCall.compareAndSet(call, null)
            }
        }
        throw IllegalStateException("HTDemucs model could not be downloaded from any source", lastFailure)
    }

    /** Close a blocked socket as soon as the download's coroutine is cancelled. */
    private suspend fun withCancellation(call: Call, transfer: suspend () -> Unit) = coroutineScope {
        val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            transfer()
        } finally {
            cancellation.cancel()
        }
    }
}
