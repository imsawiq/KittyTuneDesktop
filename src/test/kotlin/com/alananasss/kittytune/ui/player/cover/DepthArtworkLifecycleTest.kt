package com.alananasss.kittytune.ui.player.cover

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.alananasss.kittytune.core.LocalWindowSeen
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class DepthArtworkLifecycleTest {
    @Test fun `pause does not request an image or initialize depth work`() = noDepthRequest(playing = false, seen = true, scale = 1f)
    @Test fun `hidden window does not request an image or initialize depth work`() = noDepthRequest(playing = true, seen = false, scale = 1f)
    @Test fun `reduced motion does not request an image or initialize depth work`() = noDepthRequest(playing = true, seen = true, scale = 0f)

    private fun noDepthRequest(playing: Boolean, seen: Boolean, scale: Float) {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/art.png") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        val motionScale = object : MotionDurationScale { override val scaleFactor = scale }
        val url = "http://127.0.0.1:${server.address.port}/art.png"
        val scene = ImageComposeScene(120, 120, coroutineContext = Dispatchers.Unconfined + motionScale) {
            CompositionLocalProvider(LocalWindowSeen provides seen) {
                DepthArtwork(url, playing, ContentScale.Crop, Modifier.size(120.dp))
            }
        }
        try {
            scene.render(0L).close()
            Thread.sleep(150)
            scene.render(200_000_000L).close()
            assertEquals(0, requests.get(), "Inactive artwork must leave the depth pipeline idle")
        } finally {
            scene.close()
            server.stop(0)
        }
    }
}
