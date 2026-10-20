package com.alananasss.kittytune.data.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.alananasss.kittytune.core.NamedPrefs
import com.alananasss.kittytune.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the actual reorderable library/StateFlow boundary, including switching sort modes. */
class LikedTrackDragTest {
    @Test
    fun `held drag after selecting custom sort updates and persists the arrangement`() = runBlocking {
        val prefs = NamedPrefs("qa_liked_drag_${UUID.randomUUID()}")
        val store = LikedTrackOrder(prefs)
        val likes = (4L downTo 1L).map { Track(it, "Track $it", null, null, null) }
        var customSort by mutableStateOf(false)
        var displayed = emptyList<Long>()
        var moves = 0
        val scene = ImageComposeScene(width = 240, height = 320, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
            val saved by store.ids.collectAsState()
            val rows = if (customSort) orderLikedTracks(likes, saved) else likes
            displayed = rows.map { it.id }
            val listState = rememberLazyListState()
            val reorderState = rememberReorderableLazyListState(listState) { from, to ->
                if (customSort) {
                    val fromId = likes.firstOrNull { "${it.id}_dup0" == from.key }?.id
                    val toId = likes.firstOrNull { "${it.id}_dup0" == to.key }?.id
                    if (fromId != null && toId != null) {
                        store.move(likes, fromId, toId)
                        moves++
                    }
                }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(rows, key = { "${it.id}_dup0" }) { track ->
                    ReorderableItem(reorderState, key = "${track.id}_dup0") {
                        Box(Modifier.fillMaxWidth().height(48.dp).then(
                            if (customSort) Modifier.draggableHandle() else Modifier,
                        ))
                    }
                }
            }
        }
        try {
            var time = 0L
            fun frames(count: Int = 8) = repeat(count) { time += 16_000_000L; scene.render(time) }
            fun pointer(type: PointerEventType, y: Float) {
                scene.sendPointerEvent(type, Offset(120f, y), timeMillis = time / 1_000_000L,
                    type = PointerType.Touch)
                frames()
            }
            frames()
            customSort = true
            frames()
            pointer(PointerEventType.Press, 24f)
            // Give the pointer coroutine a frame at each step before releasing the handle.
            for (y in 36..120 step 12) pointer(PointerEventType.Move, y.toFloat())
            frames()
            pointer(PointerEventType.Release, 120f)
            frames(30)
            assertTrue(moves > 0, "The library must deliver an onMove callback before pointer release")
            assertEquals(listOf(3L, 2L, 4L, 1L), displayed)
            store.persist()
            customSort = false
            frames()
            assertEquals(listOf(4L, 3L, 2L, 1L), displayed)
            customSort = true
            frames()
            assertEquals(listOf(3L, 2L, 4L, 1L), displayed)
            assertEquals(displayed, orderLikedTracks(likes, LikedTrackOrder(prefs).ids.value).map { it.id })
        } finally {
            scene.close()
            prefs.clear()
        }
    }
}
