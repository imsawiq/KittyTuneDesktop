package com.alananasss.kittytune.data.artist.portrait

import com.alananasss.kittytune.data.network.ProxyManager
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object PortraitDownloads {
    suspend fun bytes(url: String, maximumBytes: Int): ByteArray = suspendCancellableCoroutine { continuation ->
        val client = ProxyManager.getOkHttpClient().newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.MINUTES)
            .build()
        val call = client.newCall(Request.Builder().url(url).get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (!it.isSuccessful) throw IOException("Portrait resource HTTP ${it.code}")
                        val body = it.body
                        if (body.contentLength() > maximumBytes) throw IOException("Portrait resource exceeds size limit")
                        body.byteStream().use { input ->
                            val output = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024))
                            val buffer = ByteArray(16 * 1024)
                            while (continuation.isActive) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (output.size() + read > maximumBytes) throw IOException("Portrait resource exceeds size limit")
                                output.write(buffer, 0, read)
                            }
                            output.toByteArray()
                        }
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (exception: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
            }
        })
    }
}
