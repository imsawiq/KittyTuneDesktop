package com.alananasss.kittytune.ui.common.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/** State changes turn the arrows; every press gets feedback even when shuffle is already enabled. */
@Composable
fun AnimatedShuffleIcon(
    active: Boolean = false,
    interactionSource: InteractionSource? = null,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    var presses by remember { mutableIntStateOf(0) }
    val pulse = remember { Animatable(0f) }
    val rotation by animateFloatAsState(if (active) 180f else 0f, tween(320), label = "shuffleRotation")
    LaunchedEffect(interactionSource) {
        interactionSource?.interactions?.collect { if (it is PressInteraction.Press) presses++ }
    }
    LaunchedEffect(presses) {
        if (presses == 0) return@LaunchedEffect
        pulse.animateTo(1f, tween(80))
        pulse.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 500f))
    }
    Icon(
        imageVector = Icons.Rounded.Shuffle,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.graphicsLayer {
            rotationZ = rotation + pulse.value * 28f
            scaleX = 1f + pulse.value * 0.16f
            scaleY = scaleX
        },
    )
}
