package com.alananasss.kittytune.data.together

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.*

class TogetherReliabilityTest {
    private val code = "ABCD2345"
    private val song = SharedTrack(1, "Song", "Artist", null, 180_000, null, "soundcloud", 2, "Uploader")

    @Before fun reset() { Together.resetForTesting() }
    @After fun cleanUp() { Together.resetForTesting() }

    @Test fun `pause position never includes latency and all positions fit track duration`() {
        assertEquals(30_000L, TogetherPlaybackPolicy.position(30_000, 180_000))
        assertEquals(180_000L, TogetherPlaybackPolicy.position(Long.MAX_VALUE, 180_000, 120))
        assertEquals(0L, TogetherPlaybackPolicy.position(Long.MIN_VALUE, 180_000))
        assertEquals(180_000L, TogetherPlaybackPolicy.position(179_900, 180_000, 3_000))
        assertEquals(86_400_000L, TogetherPlaybackPolicy.position(Long.MAX_VALUE, null, 3_000))
    }

    @Test fun `old playing packets cannot undo a newer pause`() = runBlocking {
        Together.handle(code, TogetherMessage.State("host", 200, song, 30_000, true, false, 0))
        Together.handle(code, TogetherMessage.State("host", 300, song, 30_000, false, false, 0))
        Together.handle(code, TogetherMessage.State("host", 250, song, 25_000, true, false, 0))
        assertFalse(Together.rooms.value.getValue(code).isPlaying)
    }

    @Test fun `former host state cannot steal playback back after a new claim`() = runBlocking {
        Together.handle(code, TogetherMessage.ClaimHost("new-host", 200))
        Together.handle(code, TogetherMessage.State("former-host", 300, song, 30_000, true, false, 0))
        assertEquals("new-host", Together.rooms.value.getValue(code).hostId)
        assertNull(Together.rooms.value.getValue(code).nowPlaying)
    }

    @Test fun `empty playlist from current host clears tracks and retains shared name`() = runBlocking {
        Together.handle(code, TogetherMessage.ClaimHost("host", 100))
        Together.handle(code, TogetherMessage.Queue("host", 110, 1, listOf(song), listOf(song), "Friends"))
        Together.handle(code, TogetherMessage.Queue("host", 120, 2, emptyList(), emptyList(), "Friends"))
        val room = Together.rooms.value.getValue(code)
        assertTrue(room.playlist.isEmpty())
        assertTrue(room.upcoming.isEmpty())
        assertEquals("Friends", room.name)
        Together.handle(code, TogetherMessage.Queue("host", 115, 1, listOf(song), listOf(song), "Old"))
        assertTrue(Together.rooms.value.getValue(code).playlist.isEmpty())
        assertEquals("Friends", Together.rooms.value.getValue(code).name)
    }

    @Test fun `shared tracks preserve artist separately from uploader`() {
        assertEquals("Artist", song.toTrack().displayArtist)
        assertEquals("Uploader", song.toTrack().user?.username)
    }

    @Test fun `delayed hello cannot return a departed listener to the room`() = runBlocking {
        Together.handle(code, TogetherMessage.Hello("friend", 100, "Friend", true))
        Together.handle(code, TogetherMessage.Bye("friend", 200))
        Together.handle(code, TogetherMessage.Hello("friend", 150, "Old name", true))
        Together.handle(code, TogetherMessage.Hello("friend", 200, "Same millisecond", true))

        assertTrue(Together.rooms.value.getValue(code).listeners.isEmpty())
        assertEquals("Friend", Together.rooms.value.getValue(code).members.getValue("friend").name)
    }

    @Test fun `delayed bye cannot remove a listener who has rejoined`() = runBlocking {
        Together.handle(code, TogetherMessage.Hello("friend", 100, "Friend", true))
        Together.handle(code, TogetherMessage.Bye("friend", 200))
        Together.handle(code, TogetherMessage.Hello("friend", 300, "Friend returned", true))
        Together.handle(code, TogetherMessage.Bye("friend", 200))

        assertEquals(listOf("Friend returned"), Together.rooms.value.getValue(code).listeners.map { it.name })
    }

    @Test fun `delayed departure cannot evict a host with a newer playback snapshot`() = runBlocking {
        Together.handle(code, TogetherMessage.ClaimHost("host", 100))
        Together.handle(code, TogetherMessage.State("host", 300, song, 30_000, false, false, 0))
        Together.handle(code, TogetherMessage.Bye("host", 200))

        assertEquals("host", Together.rooms.value.getValue(code).hostId)
        assertFalse(Together.rooms.value.getValue(code).isPlaying)
    }

    @Test fun `state and claim sent before departure cannot revive the old host`() = runBlocking {
        Together.handle(code, TogetherMessage.ClaimHost("host", 100))
        Together.handle(code, TogetherMessage.State("host", 110, song, 30_000, true, false, 0))
        Together.handle(code, TogetherMessage.Bye("host", 200))
        Together.handle(code, TogetherMessage.State("host", 150, song, 40_000, true, false, 0))
        Together.handle(code, TogetherMessage.ClaimHost("host", 180))

        assertNull(Together.rooms.value.getValue(code).hostId)

        Together.handle(code, TogetherMessage.ClaimHost("host", 300))
        assertEquals("host", Together.rooms.value.getValue(code).hostId)
    }

    @Test fun `presence ordering is isolated between rooms and between sender clocks`() = runBlocking {
        Together.handle(code, TogetherMessage.Bye("friend", 5_000))
        Together.handle(code, TogetherMessage.Hello("another-friend", 100, "Different clock", true))
        Together.handle("OTHER234", TogetherMessage.Hello("friend", 100, "Other room", true))

        assertEquals(listOf("Different clock"), Together.rooms.value.getValue(code).listeners.map { it.name })
        assertEquals(listOf("Other room"), Together.rooms.value.getValue("OTHER234").listeners.map { it.name })
    }
}
