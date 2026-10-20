package com.alananasss.kittytune.ui.musicimport

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.alananasss.kittytune.core.Application
import com.alananasss.kittytune.core.str
import com.alananasss.kittytune.data.musicimport.LikesImportDestination
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicImportSelectionScreenTest {
    @Test
    fun `destination rows respond to pointer clicks and survive toggling likes`() {
        val viewModel = MusicImportSelectionViewModel(Application())
        // Invalid providers return before reading saved accounts or starting a content request.
        val provider = "qa-no-provider"
        viewModel.init(provider)
        @Suppress("UNCHECKED_CAST")
        val count = MusicImportSelectionViewModel::class.java.getDeclaredField("likedTracksCount\$delegate")
            .apply { isAccessible = true }.get(viewModel) as MutableState<Int>
        count.value = 12
        viewModel.toggleLikes()
        var transfersStarted = 0
        val scene = ImageComposeScene(
            width = 900, height = 1000, density = Density(1f), coroutineContext = Dispatchers.Unconfined,
        ) {
            MaterialTheme {
                MusicImportSelectionScreen(
                    platformProviderName = provider,
                    onStartTransfer = { transfersStarted++ },
                    viewModel = viewModel,
                )
            }
        }
        try {
            var time = 0L
            fun frames() = repeat(8) { time += 16_000_000L; scene.render(time) }
            fun allNodes(): List<SemanticsNode> {
                fun descendants(node: SemanticsNode): List<SemanticsNode> =
                    listOf(node) + node.children.flatMap(::descendants)
                return scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
            }
            fun row(label: String): SemanticsNode? = allNodes().firstOrNull { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true &&
                    node.config.contains(SemanticsActions.OnClick)
            }
            fun click(label: String) {
                val target = assertNotNull(row(label), "Missing clickable row: $label").boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, target,
                    timeMillis = time / 1_000_000L, type = PointerType.Touch)
                frames()
                scene.sendPointerEvent(PointerEventType.Release, target,
                    timeMillis = time / 1_000_000L, type = PointerType.Touch)
                frames()
            }
            frames()
            val library = str("music_import_likes_destination_library")
            val playlist = str("music_import_likes_destination_playlist")
            val includeLikes = str("music_import_likes_title")
            assertNotNull(row(library))
            assertNotNull(row(playlist))
            assertEquals(LikesImportDestination.LIKED_TRACKS, viewModel.likesDestination)

            click(playlist)
            assertEquals(LikesImportDestination.NEW_PLAYLIST, viewModel.likesDestination)
            click(library)
            assertEquals(LikesImportDestination.LIKED_TRACKS, viewModel.likesDestination)
            click(playlist)
            click(includeLikes)
            assertFalse(viewModel.includeLikes)
            assertNull(row(library))
            assertNull(row(playlist))
            click(includeLikes)
            assertTrue(viewModel.includeLikes)
            assertNotNull(row(library))
            assertNotNull(row(playlist))
            assertEquals(LikesImportDestination.NEW_PLAYLIST, viewModel.likesDestination)
            assertNull(viewModel.platform)
            assertNull(viewModel.auth)
            assertFalse(viewModel.isLoading)
            assertFalse(viewModel.isImporting)
            assertEquals(0, transfersStarted)
        } finally {
            scene.close()
        }
    }
}
