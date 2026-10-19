package com.alananasss.kittytune.data.cover.depth

import com.alananasss.kittytune.data.network.ProxyManager
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.alananasss.kittytune.data.cover.depth.DepthMapCache.Companion.hex

internal object DepthTransfers {
    suspend fun image(url: String): ByteArray = request(url, 8L * 1024 * 1024, 120, {}) { input ->
        val bytes = ByteArrayOutputStream()
        input.copyTo(bytes, 16 * 1024)
        bytes.toByteArray()
    }

    /** Stream weights directly to disk; never retain a model-sized ByteArray on the application heap. */
    suspend fun model(url: String, target: File, expectedSize: Long): String =
        request(url, expectedSize, 900, { target.delete() }) { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            target.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
            digest.digest().hex()
        }

    private suspend fun <T> request(url: String, maximum: Long, timeoutSeconds: Long, discard: () -> Unit,
        consume: (InputStream) -> T): T = suspendCancellableCoroutine { continuation ->
        val client = ProxyManager.getOkHttpClient().newBuilder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
        val call = client.newCall(Request.Builder().url(url).get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                discard()
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use {
                        if (!it.isSuccessful) throw IOException("Depth resource HTTP ${it.code}")
                        if (!continuation.isActive) throw IOException("Depth request cancelled")
                        val body = it.body
                        if (body.contentLength() > maximum) throw IOException("Depth resource exceeds size limit")
                        body.byteStream().use { input -> consume(LimitedInput(input, maximum)) }
                    }
                    if (continuation.isActive) continuation.resume(value) else discard()
                } catch (exception: Exception) {
                    discard()
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
            }
        })
    }

    internal class LimitedInput(private val source: InputStream, private val maximum: Long) : InputStream() {
        private var count = 0L
        override fun read(): Int = source.read().also { if (it >= 0) increment(1) }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = source.read(bytes, offset, length)
            .also { if (it > 0) increment(it.toLong()) }
        private fun increment(size: Long) {
            count += size
            if (count > maximum) throw IOException("Depth resource exceeds size limit")
        }
    }
}
