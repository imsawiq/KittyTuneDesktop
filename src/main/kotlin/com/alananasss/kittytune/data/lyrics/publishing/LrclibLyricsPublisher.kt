package com.alananasss.kittytune.data.lyrics.publishing

import com.alananasss.kittytune.data.network.ProxyManager
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Publishes only when explicitly invoked by the editor. There are no automatic submissions or retries.
 * Contract verified against LRCLIB's official server routes and utils.rs on 2026-10-17.
 */
class LrclibLyricsPublisher(
    private val calls: Call.Factory = defaultClient(),
    private val workBudget: PublishWorkBudget = PublishWorkBudget(),
) {
    suspend fun publish(draft: LyricsPublishDraft): LyricsPublishResult {
        draft.validationError()?.let { return LyricsPublishResult.InvalidDraft(it) }
        val payload = draft.payload().toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_REQUEST_BYTES) return LyricsPublishResult.InvalidDraft("Publish request exceeds the size limit")
        return try {
            withTimeoutOrNull(TOTAL_TIMEOUT_MS) { publishValidated(payload) } ?: LyricsPublishResult.TimedOut
        } catch (_: ResponseLimitException) {
            LyricsPublishResult.ResponseTooLarge
        } catch (_: java.io.InterruptedIOException) {
            LyricsPublishResult.TimedOut
        } catch (_: IOException) {
            LyricsPublishResult.NetworkFailure
        }
    }

    private suspend fun publishValidated(payload: ByteArray): LyricsPublishResult {
        val challengeReply = post("request-challenge", ByteArray(0))
        if (challengeReply.status !in 200..299) return challengeReply.rejected(LyricsPublishResult.Stage.CHALLENGE)
        val challenge = runCatching {
            val json = JsonParser.parseString(challengeReply.body).asJsonObject
            val prefix = json.getAsJsonPrimitive("prefix").takeIf { it.isString }?.asString ?: return@runCatching null
            val target = json.getAsJsonPrimitive("target").takeIf { it.isString }?.asString ?: return@runCatching null
            LrclibChallenge(prefix, target)
        }.getOrNull()?.takeIf { it.isValid() } ?: return LyricsPublishResult.InvalidChallenge
        val nonce = LrclibProofOfWork.solve(challenge, workBudget) ?: return LyricsPublishResult.WorkLimitReached
        val reply = post("publish", payload, "${challenge.prefix}:$nonce")
        return if (reply.status == 201) LyricsPublishResult.Published else reply.rejected(LyricsPublishResult.Stage.PUBLISH)
    }

    private suspend fun post(path: String, payload: ByteArray, token: String? = null): HttpReply =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url("https://lrclib.net/api/$path")
                .header("Lrclib-Client", "KittyTuneDesktop")
                .header("Accept", "application/json")
                .post(payload.toRequestBody(JSON))
                .apply { token?.let { header("X-Publish-Token", it) } }
                .build()
            val call = calls.newCall(request)
            call.timeout().timeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    val outcome = runCatching {
                        response.use {
                            val body = response.body
                            if ((body?.contentLength() ?: 0L) > MAX_RESPONSE_BYTES) throw ResponseLimitException()
                            val bytes = body?.byteStream()?.readNBytes(MAX_RESPONSE_BYTES + 1) ?: ByteArray(0)
                            if (bytes.size > MAX_RESPONSE_BYTES) throw ResponseLimitException()
                            HttpReply(response.code, bytes.toString(Charsets.UTF_8), response.header("Retry-After")?.toLongOrNull()?.coerceIn(0L, 86_400L))
                        }
                    }
                    if (continuation.isActive) outcome.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                }
            })
        }

    private data class HttpReply(val status: Int, val body: String, val retryAfterSeconds: Long?) {
        fun rejected(stage: LyricsPublishResult.Stage): LyricsPublishResult.HttpRejected {
            val name = runCatching { JsonParser.parseString(body).asJsonObject.get("name")?.asString }.getOrNull()
                ?.takeIf { it.length <= 128 && it.all { char -> char.isLetterOrDigit() || char == '_' } }
            return LyricsPublishResult.HttpRejected(stage, status, name, retryAfterSeconds)
        }
    }

    private class ResponseLimitException : IOException()

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_REQUEST_BYTES = 524_288
        const val MAX_RESPONSE_BYTES = 16_384
        const val REQUEST_TIMEOUT_SECONDS = 15L
        const val TOTAL_TIMEOUT_MS = 65_000L
        fun defaultClient(): Call.Factory = ProxyManager.getOkHttpClient().newBuilder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
}
