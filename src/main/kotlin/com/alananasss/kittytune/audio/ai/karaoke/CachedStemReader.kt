package com.alananasss.kittytune.audio.ai.karaoke

import com.alananasss.kittytune.utils.Logger
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Independent, bounded slice access to a cached WAV; no inference or global track ownership. */
internal class CachedStemReader private constructor(
    file: File,
    val sampleRate: Int,
    val channels: Int,
    private val bitsPerSample: Int,
    private val dataOffset: Long,
    private val dataLen: Long,
) : AutoCloseable {
    private val bytesPerSample = bitsPerSample / 8
    private val frameSize = bytesPerSample * channels
    internal val totalFrames: Long = if (frameSize > 0) dataLen / frameSize else 0L
    private val raf = RandomAccessFile(file, "r")
    private var scratch = ByteArray(16 * 1024)

    /**
     * Fills [outLeft]/[outRight] with [numFrames] frames starting at [frame];
     * out-of-range frames are silence, matching the old in-RAM reader exactly.
     * Synchronized: the audio thread(s) read while [close] may swap the accessor
     * out on a track change — a close then waits for the in-flight block instead
     * of invalidating the handle under it, and the scratch buffer stays single-owner.
     */
    @Synchronized
    fun readSlice(frame: Long, outLeft: FloatArray, outRight: FloatArray, numFrames: Int): Boolean {
        if (frameSize <= 0 || numFrames <= 0) return false
        val first = frame.coerceAtLeast(0L)
        val end = (frame + numFrames).coerceAtMost(totalFrames)
        if (end <= first) {
            java.util.Arrays.fill(outLeft, 0, numFrames, 0f)
            java.util.Arrays.fill(outRight, 0, numFrames, 0f)
            return false
        }
        if (frame < 0) {
            val head = (first - frame).coerceAtMost(numFrames.toLong()).toInt()
            java.util.Arrays.fill(outLeft, 0, head, 0f)
            java.util.Arrays.fill(outRight, 0, head, 0f)
        }
        val framesToRead = (end - first).toInt()
        val outStart = (first - frame).toInt()
        val byteLen = framesToRead * frameSize
        if (scratch.size < byteLen) scratch = ByteArray(byteLen)
        try {
            raf.seek(dataOffset + first * frameSize)
            raf.readFully(scratch, 0, byteLen)
            decode(framesToRead, outStart, outLeft, outRight)
            for (i in outStart until outStart + framesToRead) {
                if (!outLeft[i].isFinite() || !outRight[i].isFinite()) {
                    java.util.Arrays.fill(outLeft, 0, numFrames, 0f)
                    java.util.Arrays.fill(outRight, 0, numFrames, 0f)
                    return false
                }
            }
        } catch (_: Exception) {
            // The old accessor was swapped out mid-read (track change): one silent
            // block, masked by the crossfade, then the new track's stems take over.
            java.util.Arrays.fill(outLeft, outStart, outStart + framesToRead, 0f)
            java.util.Arrays.fill(outRight, outStart, outStart + framesToRead, 0f)
            return false
        }
        val tailStart = outStart + framesToRead
        if (tailStart < numFrames) {
            java.util.Arrays.fill(outLeft, tailStart, numFrames, 0f)
            java.util.Arrays.fill(outRight, tailStart, numFrames, 0f)
        }
        return frame >= 0 && frame + numFrames <= totalFrames
    }

    private fun decode(frames: Int, outStart: Int, outLeft: FloatArray, outRight: FloatArray) {
        var p = 0
        if (channels == 1) {
            var i = 0
            if (bitsPerSample == 32) {
                while (i < frames) {
                    val raw = Float.fromBits(le32(scratch, p))
                val v = if (raw.isFinite()) raw.coerceIn(-1f, 1f) else raw
                    outLeft[outStart + i] = v
                    outRight[outStart + i] = v
                    i++; p += 4
                }
            } else {
                while (i < frames) {
                    val v = le16(scratch, p) / 32768f
                    outLeft[outStart + i] = v
                    outRight[outStart + i] = v
                    i++; p += 2
                }
            }
        } else {
            var i = 0
            if (bitsPerSample == 32) {
                while (i < frames) {
                    outLeft[outStart + i] = Float.fromBits(le32(scratch, p))
                    outRight[outStart + i] = Float.fromBits(le32(scratch, p + 4))
                    i++; p += 8
                }
            } else {
                while (i < frames) {
                    outLeft[outStart + i] = le16(scratch, p) / 32768f
                    outRight[outStart + i] = le16(scratch, p + 2) / 32768f
                    i++; p += 4
                }
            }
        }
    }

    private fun le16(b: ByteArray, p: Int): Int =
        (((b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)).toShort()).toInt()

    private fun le32(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
            ((b[p + 2].toInt() and 0xFF) shl 16) or (b[p + 3].toInt() shl 24)

    @Synchronized
    override fun close() {
        try { raf.close() } catch (_: Exception) {}
    }

    companion object {
        fun open(file: File): CachedStemReader? {
            if (!file.isFile || file.length() < 44L) return null
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val riff = ByteArray(12)
                    raf.readFully(riff)
                    if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                        String(riff, 8, 4, Charsets.US_ASCII) != "WAVE") return null
                    val fileLength = raf.length()
                    var channels = 0
                    var sampleRate = 0
                    var bitsPerSample = 0
                    var position = 12L
                    val chunk = ByteArray(8)
                    while (position + 8L <= fileLength) {
                        raf.seek(position)
                        raf.readFully(chunk)
                        val id = String(chunk, 0, 4, Charsets.US_ASCII)
                        val size = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).getInt(4).toLong() and 0xffffffffL
                        val start = position + 8L
                        if (size <= 0L || size > fileLength - start) return null
                        if (id == "fmt ") {
                            if (size < 16L) return null
                            val format = ByteArray(minOf(size, 40L).toInt())
                            raf.readFully(format)
                            val info = ByteBuffer.wrap(format).order(ByteOrder.LITTLE_ENDIAN)
                            var encoding = info.getShort(0).toInt() and 0xffff
                            channels = info.getShort(2).toInt()
                            sampleRate = info.getInt(4)
                            bitsPerSample = info.getShort(14).toInt()
                            if (encoding == 0xfffe) {
                                if (size < 40L || info.getShort(16).toInt() < 22 ||
                                    info.getShort(18).toInt() != bitsPerSample) return null
                                // WAVE_FORMAT_EXTENSIBLE: the tail is common to PCM and IEEE float GUIDs.
                                val guidTail = byteArrayOf(0, 0, 16, 0, -128, 0, 0, -86, 0, 56, -101, 113)
                                if (!format.copyOfRange(28, 40).contentEquals(guidTail)) return null
                                encoding = info.getInt(24)
                            }
                            if (sampleRate <= 0 || channels !in 1..2 ||
                                !((encoding == 1 && bitsPerSample == 16) ||
                                    (encoding == 3 && bitsPerSample == 32))) return null
                            if (info.getShort(12).toInt() != channels * (bitsPerSample / 8)) return null
                        } else if (id == "data") {
                            if (channels == 0 || sampleRate == 0 || bitsPerSample == 0 ||
                                size % (channels * (bitsPerSample / 8)) != 0L) return null
                            return CachedStemReader(file, sampleRate, channels, bitsPerSample, start, size)
                        }
                        position = start + size + (size and 1L)
                    }
                    null
                }
            } catch (e: Exception) {
                Logger.e("CachedStemReader", "Error opening stem file ${file.name}: ${e.message}")
                null
            }
        }
    }
}
