package com.alananasss.kittytune.data.lyrics.publishing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.security.MessageDigest

/** A local budget, below the server's five-minute challenge lifetime. No unbounded mining loop. */
data class PublishWorkBudget(val maxAttempts: Long = 50_000_000L, val maxWorkMillis: Long = 30_000L) {
    init {
        require(maxAttempts in 1..100_000_000L)
        require(maxWorkMillis in 1..60_000L)
    }
}

internal data class LrclibChallenge(val prefix: String, val target: String) {
    fun isValid(): Boolean = prefix.length in 1..128 && prefix.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } &&
        target.length == 64 && target.all { it.digitToIntOrNull(16) != null }
}

internal object LrclibProofOfWork {
    /** The header uses a colon, but the SHA256 input is prefix immediately followed by the decimal nonce. */
    suspend fun solve(challenge: LrclibChallenge, budget: PublishWorkBudget): String? = withContext(Dispatchers.Default) {
        require(challenge.isValid())
        val target = ByteArray(32) { index -> challenge.target.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        val prefix = challenge.prefix.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val started = System.nanoTime()
        for (nonce in 0L until budget.maxAttempts) {
            if (nonce % 256L == 0L) {
                currentCoroutineContext().ensureActive()
                if ((System.nanoTime() - started) / 1_000_000L >= budget.maxWorkMillis) return@withContext null
            }
            if (nonce % 8192L == 0L) yield()
            digest.update(prefix)
            val hash = digest.digest(nonce.toString().toByteArray(Charsets.UTF_8))
            if (atOrBelow(hash, target)) return@withContext nonce.toString()
        }
        null
    }

    /** Unsigned big-endian comparison, including equality, exactly as LRCLIB verifies a proof. */
    internal fun atOrBelow(hash: ByteArray, target: ByteArray): Boolean {
        require(hash.size == 32 && target.size == 32)
        for (index in hash.indices) {
            val compared = (hash[index].toInt() and 255).compareTo(target[index].toInt() and 255)
            if (compared != 0) return compared < 0
        }
        return true
    }
}
