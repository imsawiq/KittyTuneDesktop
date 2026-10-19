package com.alananasss.kittytune.data.cover.depth

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import com.alananasss.kittytune.audio.ai.OnnxHelper
import com.alananasss.kittytune.core.AppDirs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import java.util.logging.Logger
import javax.imageio.ImageIO

/** On-demand inverse-depth estimation only; no artwork upload, video generation, or startup work. */
object DepthArtworkRepository {
    private val permit = Semaphore(1)
    private val directory by lazy { File(AppDirs.cacheDir, "artwork-depth-midas-v1") }
    private val cache by lazy { DepthMapCache(File(directory, "maps")) }
    private val modelFile by lazy { DepthModelFile(directory) }
    private val failures = LinkedHashMap<String, Long>()
    private var retryModelAfter = 0L
    private val logger = Logger.getLogger("DepthArtworkRepository")

    /** Returns an 8-bit grayscale PNG: higher values mean nearer surfaces. */
    suspend fun depthFor(artworkUrl: String): File? = withContext(Dispatchers.IO) {
        val parsed = artworkUrl.takeIf { it.length <= 8192 }?.toHttpUrlOrNull() ?: return@withContext null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.toString().length > 8192) return@withContext null
        permit.withPermit {
            val now = System.currentTimeMillis()
            val key = DepthMapCache.key(parsed.toString(), "depth-v1:${DepthModelFile.SHA256}")
            try {
                cache.find(key, now)?.let { return@withPermit it }
                if ((failures[key] ?: 0) > now || retryModelAfter > now) return@withPermit null
                cache.prune(now)
                val image = DepthPixels.decode(DepthTransfers.image(parsed.toString()))
                    ?: throw IOException("Unsupported or oversized artwork")
                currentCoroutineContext().ensureActive()
                val model = try { modelFile.get() }
                catch (exception: CancellationException) { throw exception }
                catch (exception: Exception) {
                    retryModelAfter = System.currentTimeMillis() + COOLDOWN_MS
                    throw exception
                }
                val map = inferDepth(image, model) ?: throw IOException("Depth map is flat or invalid")
                currentCoroutineContext().ensureActive()
                val bytes = ByteArrayOutputStream().also { check(ImageIO.write(map, "png", it)) }.toByteArray()
                cache.store(key, bytes, System.currentTimeMillis())
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) {
                recordFailure(key, exception.javaClass.simpleName)
                null
            } catch (error: LinkageError) {
                retryModelAfter = System.currentTimeMillis() + COOLDOWN_MS
                recordFailure(key, error.javaClass.simpleName)
                null
            }
        }
    }

    private fun recordFailure(key: String, reason: String) {
        failures[key] = System.currentTimeMillis() + COOLDOWN_MS
        while (failures.size > 128) failures.remove(failures.keys.first())
        logger.fine("Artwork depth unavailable: $reason")
    }

    /** Kept internal for an opt-in real-model probe; ordinary tests never download or initialize ORT. */
    internal suspend fun inferDepth(image: BufferedImage, model: File): BufferedImage? {
        if (image.width !in 1..512 || image.height !in 1..512) return null
        currentCoroutineContext().ensureActive()
        val environment = OnnxHelper.getOrInitEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1)
            options.setCPUArenaAllocator(false); options.setMemoryPatternOptimization(false)
            environment.createSession(model.absolutePath, options).use { session ->
                currentCoroutineContext().ensureActive()
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(DepthPixels.input(image)), longArrayOf(1, 3, 256, 256)).use { tensor ->
                    session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                        currentCoroutineContext().ensureActive()
                        val output = result[0] as? OnnxTensor ?: return null
                        val shape = output.info.shape
                        if (shape.size !in 2..4 || shape.takeLast(2) != listOf(256L, 256L) || shape.dropLast(2).any { it != 1L }) return null
                        val depth = FloatArray(256 * 256)
                        output.floatBuffer.get(depth)
                        return DepthPixels.normalizedMap(depth, image.width, image.height)
                    }
                }
            }
        }
    }

    private const val COOLDOWN_MS = 10L * 60 * 1000
}
