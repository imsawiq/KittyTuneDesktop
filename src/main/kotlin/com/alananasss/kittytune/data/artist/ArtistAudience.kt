package com.alananasss.kittytune.data.artist

import com.alananasss.kittytune.data.LyricsMatcher
import com.alananasss.kittytune.data.yandex.YandexMusicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Per-service monthly audiences. The same listener may use both services, so these are never summed. */
object ArtistAudience {
    enum class Service(val label: String) {
        SPOTIFY("Spotify"),
        YANDEX("Yandex Music"),
    }

    data class Count(val service: Service, val listeners: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val yandex = ArtistLookupCache<Long>(scope, YandexMusicClient::monthlyListeners)

    suspend fun monthlyOf(artistName: String?): List<Count> = coroutineScope {
        val name = artistName?.trim().orEmpty()
        if (name.isEmpty()) return@coroutineScope emptyList()
        val spotify = async { ArtistReach.profileOf(name)?.monthlyListeners }
        val other = async {
            // Disconnection must hide cached account-derived data immediately.
            if (!YandexMusicClient.isConnected) null
            else yandex.get(LyricsMatcher.normalize(name).replace(" ", ""), name)
        }
        listOfNotNull(
            spotify.await()?.let { Count(Service.SPOTIFY, it) },
            other.await()?.let { Count(Service.YANDEX, it) },
        )
    }
}
