package com.alananasss.kittytune.ui.player

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.remember

/** Shared only by the catalog and the effects panel in one main window. */
class EffectCatalogDragState {
    var targetBounds by mutableStateOf<Rect?>(null)
    var draggedId by mutableStateOf<String?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set

    val isOverTarget: Boolean get() = draggedId != null && targetBounds?.contains(pointer) == true

    fun start(id: String, position: Offset) {
        draggedId = id
        pointer = position
    }

    fun move(delta: Offset) { pointer += delta }

    fun finish(): String? {
        val dropped = draggedId.takeIf { isOverTarget }
        draggedId = null
        return dropped
    }

    fun cancel() { draggedId = null }
}

internal fun Modifier.dragCatalogEffect(
    state: EffectCatalogDragState?,
    id: String,
    enabled: Boolean,
    onDrop: () -> Unit,
): Modifier = if (state == null || !enabled) this else composed {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    onGloballyPositioned { bounds = it.boundsInWindow() }
        .pointerInput(state, id) {
            detectDragGesturesAfterLongPress(
                onDragStart = { state.start(id, bounds.topLeft + it) },
                onDrag = { change, delta -> change.consume(); state.move(delta) },
                onDragEnd = { if (state.finish() == id) onDrop() },
                onDragCancel = state::cancel,
            )
        }
}
