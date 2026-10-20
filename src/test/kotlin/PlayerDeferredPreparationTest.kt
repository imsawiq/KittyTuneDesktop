package com.alananasss.kittytune

import com.alananasss.kittytune.media.MediaItem
import com.alananasss.kittytune.media.Player
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class PlayerDeferredPreparationTest {
    @Test
    fun firstPlayRetriesAResolutionThatFinishedWithoutAUrlWhilePaused() = runBlocking {
        val player = Player()
        try {
            player.setMediaItem(MediaItem.Builder().setMediaId("unavailable-offline").build(),
                startPositionMs = 12_000L, deferPreparation = true)
            val pausedRequest = resolveJob(player)!!
            withTimeout(2_000L) { pausedRequest.join() }
            assertEquals(12_000L, player.currentPosition)
            assertNull(player.activeEngine.currentUrl)
            assertFalse(player.playWhenReady)
            player.play()
            val retriedRequest = resolveJob(player)!!
            assertNotSame(pausedRequest, retriedRequest)
            withTimeout(2_000L) { retriedRequest.join() }
            assertEquals(12_000L, player.currentPosition)
        } finally {
            player.release()
        }
    }

    @Test
    fun playAndSeekDuringResolutionKeepThePendingPositionAndExistingRequest() = runBlocking {
        val player = Player()
        val pendingRequest = Job()
        try {
            player.setMediaItem(MediaItem.Builder().setMediaId("unavailable-offline").build(),
                startPositionMs = 12_000L, deferPreparation = true)
            withTimeout(2_000L) { resolveJob(player)!!.join() }
            player.javaClass.getDeclaredField("resolveJob").apply { isAccessible = true }.set(player, pendingRequest)
            player.seekTo(27_000L)
            player.play()
            assertSame(pendingRequest, resolveJob(player))
            assertEquals(27_000L, player.currentPosition)
            player.seekTo(31_000L)
            assertEquals(31_000L, player.currentPosition)
            assertNull(player.activeEngine.currentUrl)
            assertFalse(player.activeEngine.isPlaying)
        } finally {
            pendingRequest.cancel()
            player.release()
        }
    }

    private fun resolveJob(player: Player): Job? =
        player.javaClass.getDeclaredField("resolveJob").apply { isAccessible = true }.get(player) as Job?
}
