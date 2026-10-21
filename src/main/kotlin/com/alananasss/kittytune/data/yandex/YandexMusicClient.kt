package com.alananasss.kittytune.data.yandex

import com.alananasss.kittytune.core.NamedPrefs
import com.alananasss.kittytune.data.LyricsMatcher
import com.alananasss.kittytune.data.catalog.CatalogSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Reads the Yandex Music catalogue with the listener's own token, and only ever reads it (issue #33).
 *
 * ## What this does and the three things it deliberately does not
 *
 * Search and the fields a result carries, so that "many artists don't upload their music to soundcloud,
 * youtube, spotify" stops meaning those artists are unfindable. A hit here is a name to go and find on a
 * source that can play it — [com.alananasss.kittytune.data.catalog.CatalogFallback] does that, exactly as it
 * does for Apple Music.
 *
 * **No audio.** Yandex serves stream URLs only to an authenticated subscriber, and reaching one means
 * reproducing a signing scheme out of their own client using a secret lifted from it. That is the same thing
 * as shipping Apple's developer token out of their APK, and it is not something to do because a user asked
 * nicely. Unauthenticated it would be thirty-second previews, which he ruled out himself: "si on est pas
 * connecté, le son ne démarre pas les 30 secondes mais fait le fallback youtube / soundcloud."
 *
 * **No OAuth client of ours.** The device-code flow needs a registered application's id and secret, and the
 * obvious source for one is somebody else's client. Same objection. So the token is pasted, which is what
 * every third-party Yandex client asks for and what their own API documentation describes — see
 * [TOKEN_HELP_URL]. If a Yandex application is ever registered for KittyTune, filling in [OAUTH_CLIENT_ID]
 * is all that is needed to light up a real sign-in; nothing else here changes.
 *
 * **No pretending a region block is an empty catalogue.** Yandex answers HTTP 451 outside the countries it
 * serves — verified from France, with and without a token — so that case is reported rather than swallowed.
 * A user in Berlin searching Yandex and getting silence would reasonably conclude the feature is broken.
 */
object YandexMusicClient {

    private val prefs by lazy { NamedPrefs("yandex_music") }
    private const val TOKEN_KEY = "oauth_token"

    /** Where their own documentation sends people to obtain a token. */
    const val TOKEN_HELP_URL = "https://yandex-music.readthedocs.io/en/main/token.html"

    /**
     * Empty until KittyTune has a Yandex application of its own.
     *
     * When it does, a browser sign-in becomes possible and [canSignInThroughBrowser] starts answering true.
     * Deliberately not filled with a client id borrowed from another project.
     */
    const val OAUTH_CLIENT_ID = ""

    val canSignInThroughBrowser: Boolean get() = OAUTH_CLIENT_ID.isNotBlank()

    private const val API = "https://api.music.yandex.net"

    /** The header their API expects; not `Bearer`, which it rejects. */
    private fun authHeader(token: String) = "OAuth $token"

    /**
     * Their Android client's own identifier. Sent because the API answers differently without it, and it
     * identifies the *kind* of client rather than authenticating anything — the token does that.
     */
    private const val CLIENT_HEADER = "YandexMusicAndroid/24023621"

    // Shares ProxyManager's pool and dispatcher; only the timeouts are this client's own.
    private val baseClient by lazy {
        com.alananasss.kittytune.data.network.ProxyManager.getOkHttpClient().newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val client: OkHttpClient
        get() = com.alananasss.kittytune.data.network.ProxyManager
            .configureOkHttpClient(baseClient.newBuilder()).build()

    private val json = Json { ignoreUnknownKeys = true }

    var token: String?
        get() = prefs.getString(TOKEN_KEY, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.putString(TOKEN_KEY, value?.takeIf { it.isNotBlank() })

    val isConnected: Boolean get() = token != null

    /** What came back, so the caller can tell "nothing found" from "not available here". */
    sealed interface Result {
        data class Found(val songs: List<CatalogSong>) : Result
        /** No token stored: the catalogue is not readable at all without one. */
        data object NotConnected : Result
        /** HTTP 451 — Yandex does not serve this country. */
        data object RegionBlocked : Result
        data object Failed : Result
    }

    /** Authenticated monthly listeners only; ambiguous names and unavailable statistics stay absent. */
    suspend fun monthlyListeners(artistName: String): Long? = withContext(Dispatchers.IO) {
        val stored = token ?: return@withContext null
        val normalizedName = normalizeArtistName(artistName)
        if (normalizedName.isEmpty()) return@withContext null
        try {
            val requestClient = client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
            val search = listenerResponse(
                requestClient,
                "/search?type=artist&page=0&nocorrect=false&text=" + java.net.URLEncoder.encode(artistName, "UTF-8"),
                stored,
            ) ?: return@withContext null
            currentCoroutineContext().ensureActive()
            val matches = search["artists"]?.jsonObject?.get("results")?.jsonArray.orEmpty()
                .mapNotNull { element ->
                    val artist = element as? JsonObject ?: return@mapNotNull null
                    if (normalizeArtistName(artist["name"]?.jsonPrimitive?.contentOrNull.orEmpty()) != normalizedName) {
                        return@mapNotNull null
                    }
                    artist["id"]?.jsonPrimitive?.contentOrNull?.takeIf { id ->
                        id.isNotEmpty() && id.length <= 32 && id.all { it in '0'..'9' }
                    }
                }.distinct()
            val artistId = matches.singleOrNull() ?: return@withContext null
            val brief = listenerResponse(requestClient, "/artists/$artistId/brief-info", stored)
                ?: return@withContext null
            currentCoroutineContext().ensureActive()
            val artist = brief["artist"]?.jsonObject ?: return@withContext null
            if (artist["id"]?.jsonPrimitive?.contentOrNull != artistId ||
                normalizeArtistName(artist["name"]?.jsonPrimitive?.contentOrNull.orEmpty()) != normalizedName) {
                return@withContext null
            }
            brief["stats"]?.jsonObject?.get("lastMonthListeners")?.jsonPrimitive?.longOrNull
                ?.takeIf { it > 0L }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            null
        }
    }

    private fun normalizeArtistName(name: String): String = LyricsMatcher.normalize(name).replace(" ", "")

    private suspend fun listenerResponse(requestClient: OkHttpClient, path: String, storedToken: String): JsonObject? =
        suspendCancellableCoroutine { continuation ->
            val call = requestClient.newCall(
                Request.Builder().url(API + path)
                    .header("Authorization", authHeader(storedToken))
                    .header("X-Yandex-Music-Client", CLIENT_HEADER)
                    .build(),
            )
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            val body = it.body
                            if (!it.isSuccessful || body == null || body.contentLength() > MAX_LISTENER_RESPONSE_BYTES) {
                                null
                            } else {
                                val bytes = body.byteStream().readNBytes(MAX_LISTENER_RESPONSE_BYTES + 1)
                                if (bytes.size > MAX_LISTENER_RESPONSE_BYTES) null
                                else json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject["result"] as? JsonObject
                            }
                        }
                    } catch (_: Exception) {
                        null
                    }
                    continuation.resume(result)
                }
            })
        }

    private const val MAX_LISTENER_RESPONSE_BYTES = 2 * 1024 * 1024

    suspend fun searchSongs(term: String, limit: Int = 25): Result = withContext(Dispatchers.IO) {
        if (term.isBlank()) return@withContext Result.Found(emptyList())
        val stored = token ?: return@withContext Result.NotConnected

        val url = API + "/search?type=track&page=0&nocorrect=false&text=" +
            java.net.URLEncoder.encode(term, "UTF-8")

        val response = runCatching {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Authorization", authHeader(stored))
                    .header("X-Yandex-Music-Client", CLIENT_HEADER)
                    .build()
            ).execute().use { it.code to it.body?.string() }
        }.getOrNull() ?: return@withContext Result.Failed

        val (code, body) = response
        if (code == 451) return@withContext Result.RegionBlocked
        if (code != 200 || body == null) return@withContext Result.Failed

        runCatching {
            val tracks = json.parseToJsonElement(body).jsonObject["result"]?.jsonObject
                ?.get("tracks")?.jsonObject?.get("results")?.jsonArray
                .orEmpty()
            Result.Found(tracks.mapNotNull { YandexTrack.from(it.jsonObject) }.take(limit))
        }.getOrDefault(Result.Failed)
    }
}
