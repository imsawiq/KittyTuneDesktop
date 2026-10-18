package com.alananasss.kittytune.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alananasss.kittytune.core.str
import com.alananasss.kittytune.data.BlockManager
import com.alananasss.kittytune.data.friends.FriendListeningRepository
import com.alananasss.kittytune.domain.Track
import com.alananasss.kittytune.ui.player.cover.AnimatedArtwork

/** Only live, explicitly shared activity; names stay absent for anonymous senders. */
@Composable
internal fun FriendListeningSection(onManage: () -> Unit, onPlay: (Track) -> Unit) {
    val activities by FriendListeningRepository.activities.collectAsState()
    val blockedTracks by BlockManager.blockedTrackIdsFlow.collectAsState()
    val blockedArtists by BlockManager.blockedArtistIdsFlow.collectAsState()
    val visible = remember(activities, blockedTracks, blockedArtists) {
        activities.filterNot { BlockManager.isBlocked(it.track) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(str("friends_title"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onManage) { Text(str("friends_manage")) }
        }
        if (visible.isEmpty()) {
            Text(str("friends_empty"), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(visible, key = { it.friendCode }) { activity ->
                    Column(Modifier.width(156.dp).clip(RoundedCornerShape(12.dp)).clickable { onPlay(activity.track) }.padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        AnimatedArtwork(activity.track.artworkUrl, animatedCoverUrl = null, isPlaying = false, contentDescription = activity.track.title,
                            modifier = Modifier.size(148.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                        Text(activity.track.title.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text(activity.track.displayArtist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        activity.track.publisherMetadata?.albumTitle?.takeIf { it.isNotBlank() }?.let { album ->
                            Text(str("context_album", album), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(activity.displayName ?: str("friends_anonymous"), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
