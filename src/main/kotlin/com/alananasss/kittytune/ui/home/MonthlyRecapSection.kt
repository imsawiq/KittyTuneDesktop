package com.alananasss.kittytune.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.alananasss.kittytune.core.Strings
import com.alananasss.kittytune.core.str
import com.alananasss.kittytune.data.ListeningStatsRepository
import com.alananasss.kittytune.data.local.TopArtistResult
import com.alananasss.kittytune.data.stats.MonthlyRecap
import com.alananasss.kittytune.data.stats.MonthlyRecapWindow
import com.alananasss.kittytune.ui.common.MorphingGrid
import com.alananasss.kittytune.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Last month's actual listening. The minute tick checks calendar rollover; only revisions or a new month query data. */
@Composable
internal fun MonthlyRecapSection(onOpenStats: () -> Unit) {
    val window by produceState(initialValue = MonthlyRecap.previousCompletedMonth(System.currentTimeMillis(), ZoneId.systemDefault())) {
        while (isActive) {
            delay(60_000L)
            value = MonthlyRecap.previousCompletedMonth(System.currentTimeMillis(), ZoneId.systemDefault())
        }
    }
    val revision by ListeningStatsRepository.revision.collectAsState()
    val loaded by produceState<LoadedRecap?>(initialValue = null, window, revision) {
        value = try {
            withContext(Dispatchers.IO) {
                val artists = ListeningStatsRepository.getTopArtistsBetween(window.startMs, window.endMs, limit = 3)
                    .filter { it.totalListenMs > 0L && it.artistName.isNotBlank() }
                LoadedRecap(window, artists)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Logger.w("MonthlyRecap", "Could not load monthly recap: ${error.message}")
            null
        }
    }
    val recap = loaded?.takeIf { it.window == window && it.artists.isNotEmpty() } ?: return
    val locale = Strings.locale()
    val monthLabel = recap.window.month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
        .replaceFirstChar { it.titlecase(locale) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                str("home_monthly_recap_title", monthLabel),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenStats) { Text(str("listening_stats_show_all")) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 840.dp) recap.artists.size else 1
            MorphingGrid(columns = columns, spacing = 12.dp) {
                recap.artists.forEachIndexed { index, artist ->
                    MonthlyArtist(artist, rank = index + 1, onOpenStats = onOpenStats)
                }
            }
        }
    }
}

private data class LoadedRecap(val window: MonthlyRecapWindow, val artists: List<TopArtistResult>)

@Composable
private fun MonthlyArtist(artist: TopArtistResult, rank: Int, onOpenStats: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val hours = artist.totalListenMs / 3_600_000.0
    val formatter = NumberFormat.getNumberInstance(Strings.locale()).apply { maximumFractionDigits = 1 }
    val shownHours = if (hours < 0.1) "< ${formatter.format(0.1)}" else formatter.format(hours)
    Surface(onClick = onOpenStats, shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = scheme.onSurfaceVariant)
                artist.artworkUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("#$rank", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                Text(artist.artistName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(str("home_monthly_recap_hours", shownHours), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}
