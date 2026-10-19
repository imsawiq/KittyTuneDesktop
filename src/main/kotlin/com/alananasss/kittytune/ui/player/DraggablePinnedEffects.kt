package com.alananasss.kittytune.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.alananasss.kittytune.core.str
import kotlin.math.roundToInt

@Composable
fun DraggablePinnedTilesGrid(
    pinnedList: List<String>,
    allEffects: List<AudioFxDefinition>,
    viewModel: PlayerViewModel,
    onDragStateChanged: (Boolean) -> Unit = {},
    onRemoveFx: (String) -> Unit,
    tileContent: (@Composable (AudioFxDefinition) -> Unit)? = null,
) {
    val density = LocalDensity.current

    var currentOrder by remember(pinnedList) { mutableStateOf(pinnedList) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragTouchOffsetInItem by remember { mutableStateOf(Offset.Zero) }
    var currentFingerPos by remember { mutableStateOf(Offset.Zero) }

    val pinnedDefs = remember(currentOrder, allEffects) {
        currentOrder.mapNotNull { id -> allEffects.find { it.id == id } }
    }

    PinnedEffectsDragNotification(pinnedDefs.any { it.id == draggedId }, onDragStateChanged)

    if (pinnedDefs.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = str("edit_tiles_subtitle"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val spacingDp = 10.dp
    val itemHeightDp = if (tileContent == null) 76.dp else 88.dp

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val totalWidthPx = constraints.maxWidth.toFloat()
        val spacingPx = with(density) { spacingDp.toPx() }
        val itemHeightPx = with(density) { itemHeightDp.toPx() }
        val itemWidthPx = (totalWidthPx - spacingPx) / 2f
        val itemWidthDp = with(density) { itemWidthPx.toDp() }

        val totalRows = (currentOrder.size + 1) / 2
        val totalHeightDp = if (totalRows == 0) 0.dp else (itemHeightDp * totalRows + spacingDp * (totalRows - 1))

        Box(modifier = Modifier.fillMaxWidth().height(totalHeightDp)) {
            pinnedDefs.forEach { fx ->
                key(fx.id) {
                val index = currentOrder.indexOf(fx.id)

                val isDragging = draggedId == fx.id

                val slotCol = index % 2
                val slotRow = index / 2
                val slotXPx = slotCol * (itemWidthPx + spacingPx)
                val slotYPx = slotRow * (itemHeightPx + spacingPx)

                val targetXPx = if (isDragging) (currentFingerPos.x - dragTouchOffsetInItem.x) else slotXPx
                val targetYPx = if (isDragging) (currentFingerPos.y - dragTouchOffsetInItem.y) else slotYPx

                val animatedXPx by animateFloatAsState(
                    targetValue = targetXPx,
                    animationSpec = if (isDragging) snap() else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                    label = "itemX_${fx.id}"
                )
                val animatedYPx by animateFloatAsState(
                    targetValue = targetYPx,
                    animationSpec = if (isDragging) snap() else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                    label = "itemY_${fx.id}"
                )

                val scale by animateFloatAsState(
                    targetValue = if (isDragging) 1.08f else 1f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy),
                    label = "itemScale_${fx.id}"
                )
                val zIndex = if (isDragging) 100f else 1f

                Box(
                    modifier = Modifier
                        .offset { IntOffset(animatedXPx.roundToInt(), animatedYPx.roundToInt()) }
                        .width(itemWidthDp)
                        .height(itemHeightDp)
                        .zIndex(zIndex)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            shadowElevation = if (isDragging) 16.dp.toPx() else 0f
                            shape = RoundedCornerShape(22.dp)
                            clip = false
                        }
                        .pointerInput(fx.id, totalWidthPx, itemWidthPx, itemHeightPx, spacingPx) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offsetInItem ->
                                    val curIdx = currentOrder.indexOf(fx.id)
                                    if (curIdx != -1) {
                                        val col = curIdx % 2
                                        val row = curIdx / 2
                                        val itemOriginX = col * (itemWidthPx + spacingPx)
                                        val itemOriginY = row * (itemHeightPx + spacingPx)

                                        draggedId = fx.id
                                        dragTouchOffsetInItem = offsetInItem
                                        currentFingerPos = Offset(itemOriginX + offsetInItem.x, itemOriginY + offsetInItem.y)
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (draggedId == fx.id) {
                                        currentFingerPos += dragAmount

                                        val hoveredCol = if (currentFingerPos.x > totalWidthPx / 2f) 1 else 0
                                        val maxRow = (currentOrder.size - 1) / 2
                                        val hoveredRow = (currentFingerPos.y / (itemHeightPx + spacingPx)).toInt().coerceIn(0, maxRow)
                                        val targetIdx = (hoveredRow * 2 + hoveredCol).coerceIn(0, currentOrder.lastIndex)

                                        val curIdx = currentOrder.indexOf(fx.id)
                                        if (curIdx != -1 && targetIdx != curIdx) {
                                            currentOrder = movePinnedEffect(currentOrder, fx.id, targetIdx)
                                        }
                                    }
                                },
                                onDragEnd = {
                                    if (draggedId == fx.id) {
                                        viewModel.updatePinnedAudioFx(currentOrder)
                                        draggedId = null
                                    }
                                },
                                onDragCancel = {
                                    if (draggedId == fx.id) {
                                        currentOrder = pinnedList
                                        draggedId = null
                                    }
                                }
                            )
                        }
                ) {
                    if (tileContent != null) tileContent(fx) else ActiveQSTile(
                        fx = fx,
                        isActive = fx.isActive(viewModel.effectsState),
                        onRemove = { onRemoveFx(fx.id) }
                    )
                }
                }
            }
        }
    }
}


/** Release the parent's scroll lock even if the grid disappears during a drag. */
@Composable
internal fun PinnedEffectsDragNotification(dragging: Boolean, onDragStateChanged: (Boolean) -> Unit) {
    val notify by rememberUpdatedState(onDragStateChanged)
    DisposableEffect(dragging) {
        notify(dragging)
        onDispose { notify(false) }
    }
}
