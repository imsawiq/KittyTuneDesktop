package com.alananasss.kittytune.media

import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipPausedSeekIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `real decoder updates the picture on paused seek without resuming playback`() = runBlocking {
        val video = temporary.newFile("paused-seek.mkv")
        recordTestVideo(video)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val player = ClipPlayback(scope, mutedPreview = true)
        try {
            player.play(video.absolutePath, paused = true)
            await { player.frame != null || player.hasFailed }
            assertFalse(player.hasFailed)
            assertTrue(player.isPaused)
            assertTrue(player.durationMs >= 3_900L)
            assertEquals(RED, frameColor(player))

            val first = player.frame
            player.seekTo(3_000L)
            await { player.frame !== first || player.hasFailed }
            assertFalse(player.hasFailed)
            assertTrue(player.isPaused)
            assertEquals(3_000L, player.positionMs)
            assertEquals(BLUE, frameColor(player))

            val second = player.frame
            player.seekTo(500L)
            await { player.frame !== second || player.hasFailed }
            assertTrue(player.isPaused)
            assertEquals(500L, player.positionMs)
            assertEquals(RED, frameColor(player))
        } finally {
            player.stopAndJoin()
            scope.cancel()
        }
        assertNull(player.frame)
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(30_000L) {
        while (!condition()) delay(10L)
    }

    private fun frameColor(player: ClipPlayback): Int = requireNotNull(player.frame).asSkiaBitmap().let {
        it.getColor(it.width / 2, it.height / 2)
    }

    private fun recordTestVideo(file: File) {
        val recorder = FFmpegFrameRecorder(file, 16, 16).apply {
            format = "matroska"
            videoCodec = avcodec.AV_CODEC_ID_FFV1
            pixelFormat = avutil.AV_PIX_FMT_BGR0
            frameRate = 10.0
            gopSize = 1
        }
        try {
            recorder.start()
            Frame(16, 16, Frame.DEPTH_UBYTE, 4).use { frame ->
                val bytes = frame.image[0] as ByteBuffer
                repeat(40) { index ->
                    for (pixel in 0 until 16 * 16) {
                        bytes.put(pixel * 4, if (index < 20) 0 else -1)
                        bytes.put(pixel * 4 + 1, 0)
                        bytes.put(pixel * 4 + 2, if (index < 20) -1 else 0)
                        bytes.put(pixel * 4 + 3, -1)
                    }
                    recorder.record(frame, avutil.AV_PIX_FMT_BGRA)
                }
            }
            recorder.stop()
        } finally { recorder.release() }
    }

    private companion object {
        const val RED = 0xffff0000.toInt()
        const val BLUE = 0xff0000ff.toInt()
    }
}
