package com.alananasss.kittytune.ui.home.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Keep one lazy item while the hero and songs move between one and two columns. */
@Composable
internal fun ResponsiveSearchTopMatch(
    narrow: Boolean,
    hasTracks: Boolean,
    hero: @Composable () -> Unit,
    tracks: @Composable () -> Unit,
) {
    AnimatedContent(
        targetState = narrow,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        transitionSpec = { fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
        label = "searchTopMatchLayout",
    ) { stacked ->
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column { hero() }
                if (hasTracks) Column { tracks() }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) { hero() }
                if (hasTracks) Column(Modifier.weight(1.2f)) { tracks() }
            }
        }
    }
}
