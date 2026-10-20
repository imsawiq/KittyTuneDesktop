package com.alananasss.kittytune

import com.alananasss.kittytune.audio.AudioEngine
import com.alananasss.kittytune.ui.player.AudioEffectsState
import com.alananasss.kittytune.audio.AudioFormat
import com.alananasss.kittytune.audio.ai.karaoke.CachedStemReader
import com.alananasss.kittytune.audio.ai.karaoke.KaraokeManager
import com.alananasss.kittytune.audio.automix.AutomixStemAudioProcessor
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomixStemAudioProcessorTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun outgoingBlendRemovesOnlyTheVocalAndPreservesStereoInstrumental() {
        val file = wav("instrumental.wav", shortArrayOf(2000, -3000, 2500, -3500))
        val processor = processor()
        try {
            processor.install(assertNotNull(CachedStemReader.open(file)), 1L)
            processor.setSuppression(1L, 0.5f)
            val result = process(processor, shortArrayOf(12000, 7000, 12500, 6500))
            assertContentEquals(shortArrayOf(7000, 2000, 7500, 1500), result)
        } finally { processor.reset() }
    }

    @Test
    fun sourceTimestampSelectsThePreStretchSliceAndSeekCanMoveBackwards() {
        val file = wav("timeline.wav", ShortArray(441 * 2) { (it / 2 * 10).toShort() })
        val processor = processor()
        try {
            processor.install(assertNotNull(CachedStemReader.open(file)), 1L)
            processor.setSuppression(1L, 1f)
            processor.alignSourceTimestampUs(5_000L) // source frame 220, independent of playback tempo
            assertContentEquals(shortArrayOf(2200, 2200, 2210, 2210), process(processor, ShortArray(4)))
            processor.alignSourceTimestampUs(0L)
            assertContentEquals(shortArrayOf(0, 0, 10, 10), process(processor, ShortArray(4)))
        } finally { processor.reset() }
    }

    @Test
    fun staleProgressAndCleanupCannotAffectANewerInstalledReader() {
        val first = wav("first.wav", shortArrayOf(100, 100))
        val second = wav("second.wav", shortArrayOf(200, 200))
        val processor = processor()
        try {
            val oldReader = assertNotNull(CachedStemReader.open(first))
            processor.install(oldReader, 1L)
            processor.install(assertNotNull(CachedStemReader.open(second)), 2L)
            processor.clear(1L)
            processor.setSuppression(1L, 1f)
            assertContentEquals(shortArrayOf(999, -999), process(processor, shortArrayOf(999, -999)))
            processor.alignSourceTimestampUs(0L)
            processor.setSuppression(2L, 1f)
            assertContentEquals(shortArrayOf(200, 200), process(processor, shortArrayOf(999, -999)))
            assertFalse(oldReader.readSlice(0L, FloatArray(1), FloatArray(1), 1))
        } finally { processor.reset() }
    }

    @Test
    fun nonfiniteAndClosedStemReadsFallBackToExactDryPcm() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            val processor = processor()
            try {
                val file = wav("invalid-${value.toRawBits()}.wav", shortArrayOf(), floats = floatArrayOf(value, 0.2f))
                processor.install(assertNotNull(CachedStemReader.open(file)), 1L)
                processor.setSuppression(1L, 1f)
                val dry = shortArrayOf(12345, -23456)
                assertContentEquals(dry, process(processor, dry))
                processor.alignSourceTimestampUs(0L)
                assertContentEquals(dry, process(processor, dry))
            } finally { processor.reset() }
        }
        val processor = processor()
        try {
            val reader = assertNotNull(CachedStemReader.open(wav("closed.wav", shortArrayOf(3, 4))))
            processor.install(reader, 1L)
            processor.setSuppression(1L, 1f)
            reader.close()
            assertContentEquals(shortArrayOf(100, -100), process(processor, shortArrayOf(100, -100)))
        } finally { processor.reset() }
    }

    @Test
    fun validationRejectsWrongDurationFormatChannelsAndUncoveredRange() {
        val stereo = wav("stereo.wav", ShortArray(44100 * 2))
        val valid = AutomixStemAudioProcessor.openCachedStem(stereo, 1000L, 0L, 1000L)
        assertNotNull(valid).close()
        assertNull(AutomixStemAudioProcessor.openCachedStem(stereo, 180_000L, 0L, 1000L))
        assertNull(AutomixStemAudioProcessor.openCachedStem(stereo, 1100L, 0L, 1100L))
        val mono = wav("mono.wav", ShortArray(44100), channels = 1)
        assertNull(AutomixStemAudioProcessor.openCachedStem(mono, 1000L, 0L, 1000L))
        val wrongRate = wav("rate.wav", ShortArray(48000 * 2), rate = 48000)
        assertNull(AutomixStemAudioProcessor.openCachedStem(wrongRate, 1000L, 0L, 1000L))
        val pcm32 = wav("pcm32.wav", shortArrayOf(), floats = floatArrayOf(0.2f, 0.3f))
        val badBytes = pcm32.readBytes()
        badBytes[20] = 1 // PCM32 is not IEEE float.
        pcm32.writeBytes(badBytes)
        assertNull(CachedStemReader.open(pcm32))
        val truncated = wav("truncated.wav", ShortArray(100))
        truncated.writeBytes(truncated.readBytes().dropLast(8).toByteArray())
        assertNull(CachedStemReader.open(truncated))
    }

    @Test
    fun globalKaraokeTrackChangesDoNotCloseTheIndependentOutgoingReader() {
        val reader = assertNotNull(CachedStemReader.open(wav("independent.wav", shortArrayOf(300, -400))))
        try {
            KaraokeManager.notifyTrackChanged(null)
            KaraokeManager.notifyTrackChanged(-910011L)
            val left = FloatArray(1)
            val right = FloatArray(1)
            assertTrue(reader.readSlice(0L, left, right, 1))
            assertEquals(300f / 32768f, left[0])
            assertEquals(-400f / 32768f, right[0])
        } finally { reader.close() }
    }

    @Test
    fun oversizedBlocksUseBoundedSlicesAndFlushRestoresDryPlayback() {
        val processor = processor()
        try {
            val frames = 12_000
            processor.install(assertNotNull(CachedStemReader.open(wav("large.wav", ShortArray(frames * 2) { 123 }))), 1L)
            processor.setSuppression(1L, 1f)
            assertTrue(process(processor, ShortArray(frames * 2) { 999 }).all { it == 123.toShort() })
            processor.flush()
            assertContentEquals(shortArrayOf(999, -999), process(processor, shortArrayOf(999, -999)))
        } finally { processor.reset() }
    }

    @Test
    fun rangeExhaustionKeepsDrySamplesInsteadOfReadingSilenceAsInstrumental() {
        val processor = processor()
        try {
            processor.install(assertNotNull(CachedStemReader.open(wav("short.wav", shortArrayOf(1, 2)))), 1L)
            processor.setSuppression(1L, 1f)
            assertContentEquals(shortArrayOf(500, -500, 600, -600),
                process(processor, shortArrayOf(500, -500, 600, -600)))
            assertFalse(processor.isActive)
        } finally { processor.reset() }
    }

    @Test
    fun engineRejectsWrongTrackAdaptiveSourceAndUserVocalEffects() {
        val engine = AudioEngine()
        val file = wav("ownership.wav", ShortArray(44100 * 2))
        fun ready(container: String = "mp3") {
            engine.javaClass.getDeclaredField("state").apply { isAccessible = true }.set(engine, AudioEngine.State.READY)
            engine.javaClass.getDeclaredField("openedContainer").apply { isAccessible = true }.set(engine, container)
        }
        try {
            engine.setMediaItem("offline-source", trackId = 41L, announceTrackChange = false, knownDurationMs = 1000L)
            ready()
            val wrongOwner = assertNotNull(CachedStemReader.open(file))
            assertFalse(engine.installAutomixStem(wrongOwner, 42L, 1L))
            assertFalse(wrongOwner.readSlice(0L, FloatArray(1), FloatArray(1), 1))
            ready("hls")
            assertFalse(engine.canUseAutomixStem)
            ready()
            assertTrue(engine.installAutomixStem(assertNotNull(CachedStemReader.open(file)), 41L, 2L))
            engine.applyEffects(AudioEffectsState(isVocalRemoverEnabled = true))
            assertFalse(engine.canUseAutomixStem)
            engine.applyEffects(AudioEffectsState(isVocalBoostEnabled = true))
            assertFalse(engine.canUseAutomixStem)
            engine.applyEffects(AudioEffectsState())
            assertTrue(engine.canUseAutomixStem)
        } finally { engine.release(preserveTrackState = true) }
    }

    @Test
    fun extensibleFloatAndPcmStemsRemainReadableByKaraokeAndAutomix() {
        val floatFile = wav("float-extensible.wav", shortArrayOf(), floats = floatArrayOf(0.25f, -0.5f))
        extensible(floatFile, 3)
        val pcmFile = wav("pcm-extensible.wav", shortArrayOf(123, -456))
        extensible(pcmFile, 1)
        assertNotNull(CachedStemReader.open(pcmFile)).use { reader ->
            val left = FloatArray(1)
            val right = FloatArray(1)
            assertTrue(reader.readSlice(0L, left, right, 1))
            assertEquals(123f / 32768f, left[0])
        }
        val trackId = -910012L
        val cache = KaraokeManager.getStemFile(trackId)
        try {
            cache.writeBytes(floatFile.readBytes())
            assertTrue(KaraokeManager.loadStemSync(trackId))
            val left = FloatArray(1)
            val right = FloatArray(1)
            assertTrue(KaraokeManager.readStemSlice(trackId, 0L, left, right, 1))
            assertEquals(0.25f, left[0])
            assertEquals(-0.5f, right[0])
            assertNotNull(CachedStemReader.open(cache)).use { independent ->
                KaraokeManager.notifyTrackChanged(null)
                assertTrue(independent.readSlice(0L, left, right, 1))
                assertEquals(0.25f, left[0])
            }
        } finally {
            KaraokeManager.notifyTrackChanged(null)
            cache.delete()
        }
        val valid = floatFile.readBytes()
        val invalid = valid.copyOf()
        invalid[59] = 0 // Invalid GUID tail.
        floatFile.writeBytes(invalid)
        assertNull(CachedStemReader.open(floatFile))
        val pcm32 = valid.copyOf()
        pcm32[44] = 1 // Extensible PCM32 integer must not be interpreted as float.
        floatFile.writeBytes(pcm32)
        assertNull(CachedStemReader.open(floatFile))
        floatFile.writeBytes(valid.copyOf(58)) // Truncated GUID/fmt payload.
        assertNull(CachedStemReader.open(floatFile))
    }

    private fun extensible(file: File, encoding: Int) {
        val original = file.readBytes()
        val originalHeader = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
        val data = original.copyOfRange(44, original.size)
        val buffer = ByteBuffer.allocate(68 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(60 + data.size).put("WAVEfmt ".toByteArray()).putInt(40)
        buffer.putShort(0xfffe.toShort()).put(original, 22, 14) // channels, rate, byte rate, alignment, bits
        buffer.putShort(22).putShort(originalHeader.getShort(34)).putInt(3)
        buffer.putInt(encoding).put(byteArrayOf(0, 0, 16, 0, -128, 0, 0, -86, 0, 56, -101, 113))
        buffer.put("data".toByteArray()).putInt(data.size).put(data)
        file.writeBytes(buffer.array())
    }

    private fun processor() = AutomixStemAudioProcessor().also { it.configure(AudioFormat(44100, 2)) }

    private fun process(processor: AutomixStemAudioProcessor, pcm: ShortArray): ShortArray {
        val input = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { input.putShort(it) }
        input.flip()
        processor.queueInput(input)
        val result = processor.getOutput().asShortBuffer()
        return ShortArray(result.remaining()).also { result.get(it) }
    }

    private fun wav(name: String, pcm: ShortArray, channels: Int = 2, rate: Int = 44100, floats: FloatArray? = null): File {
        val dataSize = floats?.size?.times(4) ?: (pcm.size * 2)
        val bytesPerSample = if (floats != null) 4 else 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
        buffer.putInt(16).putShort((if (floats != null) 3 else 1).toShort()).putShort(channels.toShort())
        buffer.putInt(rate).putInt(rate * channels * bytesPerSample).putShort((channels * bytesPerSample).toShort())
        buffer.putShort((bytesPerSample * 8).toShort()).put("data".toByteArray()).putInt(dataSize)
        if (floats != null) floats.forEach { buffer.putFloat(it) } else pcm.forEach { buffer.putShort(it) }
        return files.newFile(name).also { it.writeBytes(buffer.array()) }
    }
}
