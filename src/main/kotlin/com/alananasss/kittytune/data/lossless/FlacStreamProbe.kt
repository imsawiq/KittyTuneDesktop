package com.alananasss.kittytune.data.lossless

import com.alananasss.kittytune.data.network.ProxyManager
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Reads only the format signature, and closes responses even if Range was ignored. */
internal class FlacStreamProbe(private val calls: Call.Factory = ProxyManager.getOkHttpClient()) {
    suspend fun verify(url: String): Boolean = suspendCancellableCoroutine { continuation ->
        val request = runCatching { Request.Builder().url(url).header("Range", "bytes=0-3").build() }.getOrNull()
        if (request == null) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }
        val call = calls.newCall(request)
        call.timeout().timeout(5, TimeUnit.SECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(false)
            }
            override fun onResponse(call: Call, response: Response) {
                val valid = runCatching {
                    response.use {
                        it.isSuccessful && it.body?.byteStream()?.readNBytes(4)
                            ?.contentEquals(byteArrayOf(102, 76, 97, 67)) == true
                    }
                }.getOrDefault(false)
                if (continuation.isActive) continuation.resume(valid)
            }
        })
    }
}
