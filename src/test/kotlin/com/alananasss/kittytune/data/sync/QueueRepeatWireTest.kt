package com.alananasss.kittytune.data.sync

import com.alananasss.kittytune.domain.Track
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueRepeatWireTest {
    private fun state() = PlaybackSnapshot("renderer", 1_000,
        listOf(Track(id = 1, title = "Track", artworkUrl = null, durationMs = 120_000, user = null)),
        0, 10_000, false, false, "NONE")

    @Test
    fun `armed future repeat round trips without setting current repeat mode`() {
        val original = state().copy(pendingRepeatTrackId = 2, pendingRepeatTrackSource = "youtube")
        val credentials = ConnectCredentials.derive("renderer", "a".repeat(32), "phone", "b".repeat(32))
        val message = ConnectMessage(kind = "state", sender = "renderer", session = "s".repeat(32), sequence = 1, state = original)
        val decoded = assertNotNull(ConnectWire.open(credentials, ConnectWire.seal(credentials, message))).state!!
        assertEquals(2L, decoded.pendingRepeatTrackId)
        assertEquals("youtube", decoded.pendingRepeatTrackSource)
        assertEquals("NONE", decoded.repeatMode)
        val oldJson = ConnectWire.serializeSnapshot(state())
        val old = ConnectWire.deserializeSnapshot(oldJson)
        assertNull(old.pendingRepeatTrackId)
        assertNull(old.pendingRepeatTrackSource)
    }

    @Test
    fun `a paused renderer publishes pending repeat changes in a compact state frame`() {
        val publisher = ConnectStatePublisher()
        val before = state()
        publisher.plan(before, nowNanos = 0)
        val armed = before.copy(pendingRepeatTrackId = 1, pendingRepeatTrackSource = "soundcloud")
        val frame = assertNotNull(publisher.plan(armed, nowNanos = 1))
        assertTrue(frame.state.queue.isEmpty())
        assertEquals(1L, frame.state.pendingRepeatTrackId)
        assertNotNull(publisher.plan(before, nowNanos = 2), "Cancellation must also reach a paused controller")
    }
}
