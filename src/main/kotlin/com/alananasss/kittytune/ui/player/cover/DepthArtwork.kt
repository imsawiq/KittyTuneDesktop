package com.alananasss.kittytune.ui.player.cover

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.layout.ContentScale
import coil3.BitmapImage
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.alananasss.kittytune.core.LocalWindowSeen
import com.alananasss.kittytune.data.cover.depth.DepthArtworkRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Bitmap

/** Leaves the static cover underneath until a real, non-flat depth map and both textures are ready. */
@Composable
internal fun DepthArtwork(artworkUrl: String, isPlaying: Boolean, contentScale: ContentScale, modifier: Modifier) {
    val seen = LocalWindowSeen.current
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    var renderer by remember(artworkUrl) { mutableStateOf<DepthArtworkRenderer?>(null) }
    var seconds by remember(artworkUrl) { mutableFloatStateOf(0f) }
    val appear by animateFloatAsState(if (renderer == null) 0f else 1f, tween(450), label = "depthCoverReveal")

    LaunchedEffect(artworkUrl, isPlaying, seen, motionScale) {
        if (!isPlaying || !seen || motionScale <= 0f) return@LaunchedEffect
        if (renderer == null) {
            val source = loadDepthSource(artworkUrl) ?: return@LaunchedEffect
            // Allocate owned native textures after the cancellable IO boundary, so a cancelled
            // withContext cannot discard a native renderer before Compose can dispose it.
            renderer = createDepthRenderer(source) ?: return@LaunchedEffect
        }
        var last = System.nanoTime()
        while (true) {
            delay(33)
            val now = System.nanoTime()
            seconds = (seconds + ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f) / motionScale) % 16f
            last = now
        }
    }
    DisposableEffect(renderer) {
        val scoped = renderer
        onDispose { scoped?.close() }
    }
    renderer?.let { ready ->
        Canvas(modifier.graphicsLayer { alpha = appear }) {
            drawIntoCanvas { ready.draw(it.skiaCanvas, size.width, size.height, seconds, contentScale) }
        }
    }
}

private data class DepthSource(val artwork: Bitmap, val depthBytes: ByteArray)

private suspend fun loadDepthSource(url: String): DepthSource? = withContext(Dispatchers.IO) {
    try {
        val mapFile = DepthArtworkRepository.depthFor(url) ?: return@withContext null
        val result = SingletonImageLoader.get(PlatformContext.INSTANCE).execute(
            ImageRequest.Builder(PlatformContext.INSTANCE).data(url).size(512).build(),
        )
        val bitmap = ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap ?: return@withContext null
        currentCoroutineContext().ensureActive()
        DepthSource(bitmap, mapFile.readBytes())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { null }
}

private fun createDepthRenderer(source: DepthSource): DepthArtworkRenderer? {
    var artwork: Image? = null
    var depth: Image? = null
    try {
        val art = Image.makeFromBitmap(source.artwork)
        artwork = art
        val map = Image.makeFromEncoded(source.depthBytes)
        depth = map
        return DepthArtworkRenderer.create(art, map).also { artwork = null; depth = null }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return null
    } finally {
        artwork?.close()
        depth?.close()
    }
}
