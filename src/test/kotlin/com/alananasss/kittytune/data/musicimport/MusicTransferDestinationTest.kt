package com.alananasss.kittytune.data.musicimport

import com.google.gson.Gson
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicTransferDestinationTest {
    private val request = MusicTransferRequest(
        platform = MusicApi.SPOTIFY,
        userPlatformUuid = "test-platform-user",
        playlistIds = listOf("selected-playlist"),
        importLikes = true,
        likedTrackCount = 50
    )

    @Test
    fun `existing imports still add songs to liked tracks by default`() {
        assertEquals(LikesImportDestination.LIKED_TRACKS, request.likesDestination)
        assertFalse(request.importLikesAsPlaylists)
    }

    @Test
    fun `separate playlist destination enables the server playlist flag`() {
        val playlistRequest = request.copy(likesDestination = LikesImportDestination.NEW_PLAYLIST)

        val input = MusicImportGraphQL.StartLikesSyncInput(
            userPlatformUuid = playlistRequest.userPlatformUuid,
            importAsPlaylists = playlistRequest.importLikesAsPlaylists
        )
        val payload = Gson().toJsonTree(input).asJsonObject

        assertTrue(payload.get("importAsPlaylists").asBoolean)
        assertEquals("test-platform-user", payload.get("userPlatformUuid").asString)
        assertEquals(listOf("selected-playlist"), playlistRequest.playlistIds)
    }

    @Test
    fun `disabling likes prevents playlist import despite remembered destination`() {
        val playlistsOnly = request.copy(
            importLikes = false,
            likesDestination = LikesImportDestination.NEW_PLAYLIST
        )

        assertFalse(playlistsOnly.importLikesAsPlaylists)
    }
}
