package com.alananasss.kittytune.audio.ai.karaoke

import java.io.File
import java.security.MessageDigest

/** The F16 weights used by demucs-rs, pinned to the publisher's immutable revision. */
internal object HTDemucsModelIntegrity {
    const val SIZE = 84_030_696L
    const val SHA256 = "8193504cdfb3943adaf039b8acb524a46e87ebf232c383ac7a32c80a6578423e"
    const val URL = "https://huggingface.co/set-soft/audio_separation/resolve/939723568b2dca203e61cc7294317ba38549964f/Demucs/htdemucs.safetensors"

    fun requireValid(file: File) {
        require(file.isFile && file.length() == SIZE) { "HTDemucs model download is incomplete" }
        require(sha256(file) == SHA256) { "HTDemucs model checksum does not match" }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
