package com.alananasss.kittytune.data.cover.depth

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest
import com.alananasss.kittytune.data.cover.depth.DepthMapCache.Companion.hex

/** Accessed only inside the repository's single permit. */
internal class DepthModelFile(private val directory: File) {
    private var verifiedModifiedAt = Long.MIN_VALUE

    suspend fun get(): File {
        directory.mkdirs()
        directory.listFiles()?.filter { it.isFile && it.name.startsWith("midas-") && it.extension == "part" }
            ?.forEach { it.delete() }
        val model = File(directory, "midas-v21-small.onnx")
        if (model.isFile && model.length() == SIZE_BYTES) {
            if (model.lastModified() == verifiedModifiedAt || digest(model) == SHA256) {
                verifiedModifiedAt = model.lastModified()
                return model
            }
        }
        val temporary = File.createTempFile("midas-", ".part", directory)
        try {
            val digest = DepthTransfers.model(URL, temporary, SIZE_BYTES)
            check(temporary.length() == SIZE_BYTES && digest == SHA256) { "Depth model integrity check failed" }
            currentCoroutineContext().ensureActive()
            DepthMapCache.replace(temporary, model)
            verifiedModifiedAt = model.lastModified()
            return model
        } finally { temporary.delete() }
    }

    private suspend fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().hex()
    }

    companion object {
        const val SIZE_BYTES = 66_764_249L
        // Computed from the canonical release asset, downloaded and size-checked on 2026-10-19.
        const val SHA256 = "2d8c6cb8f415229daf1eb041024208e2608c9f98e17c81cc7c6ecb449c56fd58"
        const val URL = "https://github.com/isl-org/MiDaS/releases/download/v2_1/model-small.onnx"
    }
}
