package com.alananasss.kittytune.data.discord

import com.alananasss.kittytune.data.local.DiscordStatusDisplay
import com.alananasss.kittytune.domain.Track
import com.google.gson.JsonParser
import org.junit.Test
import kotlin.test.*

class DiscordPresencePayloadTest {
    private val track = Track(1, "A song", null, 180_000, null,
        permalinkUrl = "https://soundcloud.com/artist/song")

    @Test fun `playing timestamps account for position and clamp seeks`() {
        val presence = JsonParser.parseString(DiscordPresencePayload.activity(
            track, "My playlist", true, 200_000, DiscordStatusDisplay.SONG, nowMs = 1_000_000)).asJsonObject
        assertEquals("A song", presence["details"].asString)
        assertEquals(820L, presence["timestamps"].asJsonObject["start"].asLong)
        assertEquals(1000L, presence["timestamps"].asJsonObject["end"].asLong)
        assertEquals("My playlist", presence["assets"].asJsonObject["large_text"].asString)
    }

    @Test fun `paused presence has no running timer`() {
        val presence = JsonParser.parseString(DiscordPresencePayload.activity(
            track, null, false, 10_000, DiscordStatusDisplay.ACTIVITY)).asJsonObject
        assertFalse(presence.has("timestamps"))
        assertTrue(presence["state"].asString.startsWith("Paused"))
    }

    @Test fun `local artwork and unsafe links never leave through presence`() {
        val local = track.copy(artworkUrl = "file:///private/cover.jpg", permalinkUrl = "javascript:alert(1)")
        val presence = JsonParser.parseString(DiscordPresencePayload.activity(
            local, null, true, 0, DiscordStatusDisplay.ACTIVITY)).asJsonObject
        assertFalse(presence.has("buttons"))
        assertEquals("kittytune", presence["assets"].asJsonObject["large_image"].asString)
    }

    @Test fun `clear command contains null activity and current process`() {
        val command = JsonParser.parseString(DiscordPresencePayload.command(null)).asJsonObject
        assertEquals("SET_ACTIVITY", command["cmd"].asString)
        assertTrue(command["args"].asJsonObject["activity"].isJsonNull)
        assertEquals(ProcessHandle.current().pid(), command["args"].asJsonObject["pid"].asLong)
        assertTrue(command["nonce"].asString.isNotBlank())
    }
}
