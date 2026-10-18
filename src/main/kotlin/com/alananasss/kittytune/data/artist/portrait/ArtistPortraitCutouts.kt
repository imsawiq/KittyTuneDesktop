package com.alananasss.kittytune.data.artist.portrait

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
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.logging.Logger
import javax.imageio.ImageIO

/** On-demand, local-only portrait segmentation. A null result keeps the ordinary artist avatar. */
object ArtistPortraitCutouts {
    private val gate = Semaphore(1)
    private val directory by lazy { File(AppDirs.cacheDir, "artist-portraits-u2netp-v1") }
    private val cache by lazy { PortraitCache(File(directory, "images")) }
    private val failedUrls = LinkedHashMap<String, Long>()
    private var modelRetryAt = 0L
    private val logger = Logger.getLogger("ArtistPortraitCutouts")

    suspend fun cutout(url: String): File? = withContext(Dispatchers.IO) {
        val parsed = url.toHttpUrlOrNull() ?: return@withContext null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return@withContext null
        gate.withPermit {
            val now = System.currentTimeMillis()
            val key = PortraitCache.digest(("u2netp-v2:" + parsed.toString()).toByteArray(Charsets.UTF_8))
            cache.find(key, now)?.let { return@withPermit it }
            if ((failedUrls[key] ?: 0) > now || modelRetryAt > now) return@withPermit null
            try {
                cache.prune(now)
                val image = PortraitPixels.decode(PortraitDownloads.bytes(parsed.toString(), MAX_IMAGE_BYTES))
                    ?: throw IOException("Unsupported or oversized portrait")
                currentCoroutineContext().ensureActive()
                val model = try {
                    modelFile()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    modelRetryAt = now + FAILURE_COOLDOWN
                    throw exception
                }
                val result = infer(image, model) ?: throw IOException("Portrait mask is not usable")
                currentCoroutineContext().ensureActive()
                val bytes = ByteArrayOutputStream().also { output ->
                    check(ImageIO.write(result, "png", output)) { "PNG encoder unavailable" }
                }.toByteArray()
                cache.store(key, bytes, now)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                recordFailure(key, now, exception.javaClass.simpleName)
                null
            } catch (error: LinkageError) {
                modelRetryAt = now + FAILURE_COOLDOWN
                recordFailure(key, now, error.javaClass.simpleName)
                null
            }
        }
    }

    private fun recordFailure(key: String, now: Long, reason: String) {
        failedUrls[key] = now + FAILURE_COOLDOWN
        while (failedUrls.size > 128) failedUrls.remove(failedUrls.keys.first())
        // Do not log signed artwork URLs or image contents.
        logger.fine("Portrait cutout unavailable: $reason")
    }

    private suspend fun modelFile(): File {
        directory.mkdirs()
        val model = File(directory, "u2netp.onnx")
        if (model.isFile && model.length() == MODEL_BYTES.toLong() && PortraitCache.digest(model.readBytes()) == MODEL_SHA256) return model
        val bytes = PortraitDownloads.bytes(MODEL_URL, MODEL_BYTES)
        check(bytes.size == MODEL_BYTES && PortraitCache.digest(bytes) == MODEL_SHA256) { "Portrait model integrity check failed" }
        currentCoroutineContext().ensureActive()
        val temporary = File.createTempFile("u2netp-", ".part", directory)
        try {
            temporary.writeBytes(bytes)
            try {
                Files.move(temporary.toPath(), model.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), model.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
        return model
    }

    internal suspend fun infer(image: BufferedImage, model: File): BufferedImage? {
        currentCoroutineContext().ensureActive()
        val environment = OnnxHelper.getOrInitEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            options.setInterOpNumThreads(1)
            options.setCPUArenaAllocator(false)
            options.setMemoryPatternOptimization(false)
            environment.createSession(model.absolutePath, options).use { session ->
                currentCoroutineContext().ensureActive()
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(PortraitPixels.input(image)), longArrayOf(1, 3, 320, 320)).use { tensor ->
                    session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                        currentCoroutineContext().ensureActive()
                        val output = result[0] as? OnnxTensor ?: return null
                        if (!output.info.shape.contentEquals(longArrayOf(1, 1, 320, 320))) return null
                        val mask = FloatArray(320 * 320)
                        output.floatBuffer.get(mask)
                        return PortraitPixels.cutout(image, mask)
                    }
                }
            }
        }
    }

    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val FAILURE_COOLDOWN = 10L * 60 * 1000
    private const val MODEL_BYTES = 4_574_861
    private const val MODEL_SHA256 = "309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8"
    // U-2-Net / rembg model: Apache-2.0, https://github.com/xuebinqin/U-2-Net
    private const val MODEL_URL = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx"
}
