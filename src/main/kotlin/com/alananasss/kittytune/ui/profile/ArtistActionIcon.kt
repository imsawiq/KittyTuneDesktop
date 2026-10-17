package com.alananasss.kittytune.ui.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/** The artist's follow and pin states change within a stable round button. */
@Composable
internal fun ArtistActionIcon(
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    contentDescription: String?,
) {
    AnimatedContent(
        targetState = selected,
        transitionSpec = {
            (fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.8f)) togetherWith
                (fadeOut(tween(130)) + scaleOut(tween(160), targetScale = 0.8f))
        },
        label = "artistActionIcon",
    ) { active ->
        Icon(
            if (active) selectedIcon else unselectedIcon,
            contentDescription = contentDescription,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
