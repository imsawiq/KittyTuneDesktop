package com.alananasss.kittytune.ui.musicimport

import com.alananasss.kittytune.core.Application
import com.alananasss.kittytune.data.musicimport.LikesImportDestination
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicImportSelectionDestinationTest {
    @Test
    fun `likes are opt in and initially target KittyTune liked tracks`() {
        val viewModel = MusicImportSelectionViewModel(Application())

        assertFalse(viewModel.includeLikes)
        assertEquals(LikesImportDestination.LIKED_TRACKS, viewModel.likesDestination)
    }

    @Test
    fun `toggling likes preserves the chosen destination and selected playlists`() {
        val viewModel = MusicImportSelectionViewModel(Application())
        viewModel.togglePlaylist("my-playlist")
        viewModel.selectLikesDestination(LikesImportDestination.NEW_PLAYLIST)

        viewModel.toggleLikes()
        viewModel.toggleLikes()
        viewModel.toggleLikes()

        assertTrue(viewModel.includeLikes)
        assertEquals(LikesImportDestination.NEW_PLAYLIST, viewModel.likesDestination)
        assertEquals(setOf("my-playlist"), viewModel.selectedPlaylistIds)
    }
}
