package com.alananasss.kittytune.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import androidx.compose.runtime.CompositionLocalProvider
import com.alananasss.kittytune.core.LocalWindowSeen
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtistLookupStateTest {
    @Test
    fun `mounted profiles refresh without clearing current data and stop while hidden or disposed`() {
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val refreshed = CompletableDeferred<String?>()
        var seen by mutableStateOf(true)
        var displayed: String? = null
        var calls = 0
        val scene = ImageComposeScene(20, 20, coroutineContext = Dispatchers.Unconfined) {
            CompositionLocalProvider(LocalWindowSeen provides seen) {
                displayed = rememberArtistLookup("Artist", waitForRefresh = { ticks.receive() }) {
                    if (++calls == 1) "Initial profile" else refreshed.await()
                }
            }
        }
        var frame = 0L
        fun render() { repeat(3) { scene.render(++frame * 10_000_000L).close() } }
        try {
            render()
            assertEquals("Initial profile", displayed)
            ticks.trySend(Unit)
            render()
            assertEquals("Initial profile", displayed, "Refresh must retain data until the response arrives")
            refreshed.complete("Updated profile")
            render()
            assertEquals("Updated profile", displayed)
            seen = false
            render()
            ticks.trySend(Unit)
            render()
            assertEquals(2, calls, "A hidden window must not request artist data")
            seen = true
            render()
            ticks.trySend(Unit)
            render()
            assertEquals(3, calls)
        } finally { scene.close() }
        ticks.trySend(Unit)
        assertEquals(3, calls, "Disposal must cancel the refresh loop")
        ticks.close()
    }

    @Test
    fun `changing artist hides the previous result before the new lookup completes`() {
        var artist by mutableStateOf<String?>("First")
        var displayed: String? = null
        val first = CompletableDeferred<String?>().apply { complete("First profile") }
        val second = CompletableDeferred<String?>()
        val results = mapOf("First" to first, "Second" to second)
        val scene = ImageComposeScene(20, 20, coroutineContext = Dispatchers.Unconfined) {
            displayed = rememberArtistLookup(artist) { name -> results[name]?.await() }
        }
        try {
            repeat(3) { scene.render(it * 10_000_000L).close() }
            assertEquals("First profile", displayed)

            artist = "Second"
            scene.render(40_000_000L).close()
            assertNull(displayed, "A different artist must never display the previous artist's data")

            second.complete("Second profile")
            repeat(3) { scene.render(50_000_000L + it * 10_000_000L).close() }
            assertEquals("Second profile", displayed)

            artist = null
            scene.render(90_000_000L).close()
            assertNull(displayed)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a late response for the previous artist cannot replace the current result`() {
        var artist by mutableStateOf("First")
        var displayed: String? = null
        val first = CompletableDeferred<String?>()
        val second = CompletableDeferred<String?>().apply { complete("Second banner") }
        val results = mapOf("First" to first, "Second" to second)
        val scene = ImageComposeScene(20, 20, coroutineContext = Dispatchers.Unconfined) {
            displayed = rememberArtistLookup(artist) { name -> results[name]?.await() }
        }
        try {
            scene.render(0L).close()
            assertNull(displayed)

            artist = "Second"
            repeat(3) { scene.render(10_000_000L + it * 10_000_000L).close() }
            assertEquals("Second banner", displayed)

            first.complete("First banner")
            repeat(3) { scene.render(50_000_000L + it * 10_000_000L).close() }
            assertEquals("Second banner", displayed)
        } finally {
            scene.close()
        }
    }
}
