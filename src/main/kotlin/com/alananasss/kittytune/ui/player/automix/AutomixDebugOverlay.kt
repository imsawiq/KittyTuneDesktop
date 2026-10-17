package com.alananasss.kittytune.ui.player.automix

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alananasss.kittytune.audio.automix.AutomixManager
import com.alananasss.kittytune.core.str
import com.alananasss.kittytune.utils.makeTimeString

/** A compact transition monitor; detailed analysis stays available without occupying the player. */
@Composable
fun AutomixDebugOverlay(currentPositionMs: () -> Long, modifier: Modifier = Modifier) {
    val visible by AutomixManager.isDebugOverlayVisible.collectAsState()
    val debug by AutomixManager.automixDebugInfo.collectAsState()
    val mixing by AutomixManager.isAutomixing.collectAsState()
    val beatsLeft by AutomixManager.mixBeatsLeft.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val statusKey = when {
        mixing -> "automix_monitor_mixing"
        debug?.triggerTimeMs != null -> "automix_monitor_ready"
        debug?.status?.startsWith("Analyzing", ignoreCase = true) == true -> "automix_monitor_analyzing"
        debug?.status?.startsWith("fallback", ignoreCase = true) == true -> "automix_monitor_fallback"
        else -> "automix_monitor_standby"
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { -it / 4 },
        exit = fadeOut() + slideOutVertically { -it / 4 },
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(max = 360.dp).heightIn(max = 440.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(str("automix_monitor_title"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(str(statusKey), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, str("automix_monitor_details"))
                    }
                    IconButton(onClick = { AutomixManager.setDebugOverlayEnabled(false) }) {
                        Icon(Icons.Rounded.Close, str("btn_close"))
                    }
                }
                MonitorTrack(str("automix_monitor_current"), debug?.outTitle, debug?.outBpm, debug?.outCamelot ?: debug?.outKey)
                MonitorTrack(str("automix_monitor_next"), debug?.inTitle, debug?.inBpm, debug?.inCamelot ?: debug?.inKey)
                val trigger = debug?.triggerTimeMs
                if (!mixing && trigger != null) {
                    val seconds = ((trigger - currentPositionMs()) / 1000).coerceAtLeast(0L)
                    Text(str("automix_monitor_countdown", seconds), style = MaterialTheme.typography.bodySmall)
                } else if (mixing && beatsLeft != null) {
                    Text(str("automix_monitor_beats", beatsLeft ?: 0), style = MaterialTheme.typography.bodySmall)
                }
                AnimatedVisibility(expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HorizontalDivider()
                        debug?.status?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        debug?.let { info ->
                            val details = listOfNotNull(
                                info.overlapMs?.let { str("automix_monitor_overlap", "%.1f".format(it / 1000f)) },
                                info.incomingStartMs?.let { str("automix_monitor_start", makeTimeString(it)) },
                                info.tempoRatio?.takeIf { it != 1f }?.let { "Tempo ×%.3f".format(it) },
                            )
                            details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitorTrack(label: String, title: String?, bpm: Float?, key: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title ?: "—", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        val features = listOfNotNull(bpm?.takeIf { it.isFinite() && it > 0f }?.let { "%.1f BPM".format(it) }, key)
        if (features.isNotEmpty()) Text(features.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
