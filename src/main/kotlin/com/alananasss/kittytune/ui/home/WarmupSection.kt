package com.alananasss.kittytune.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alananasss.kittytune.core.NamedPrefs
import com.alananasss.kittytune.core.str
import com.alananasss.kittytune.data.DownloadManager
import com.alananasss.kittytune.data.LikeRepository
import com.alananasss.kittytune.data.local.AppDatabase
import com.alananasss.kittytune.data.mix.GeneratedPlaylists
import com.alananasss.kittytune.data.mix.WarmupQueue
import com.alananasss.kittytune.ui.common.ScrollableLazyRow
import com.alananasss.kittytune.ui.main.MediaCard
import com.alananasss.kittytune.ui.player.PlayerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WarmupSection(player: PlayerViewModel) {
    val likes by LikeRepository.likedTracks.collectAsState()
    val playlistFlow = remember { DownloadManager.getAllPlaylistsFlow() }
    val playlists by playlistFlow.collectAsState(initial = emptyList())
    val prefs = remember { NamedPrefs("warmup_playlists") }
    val scope = rememberCoroutineScope()
    var building by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf(false) }
    val savedIds = prefs.getString("ids", "").orEmpty().split(',').mapNotNull(String::toLongOrNull).toSet()
    val saved = playlists.filter { it.id in savedIds }
    val candidates = likes.filterNot(com.alananasss.kittytune.data.BlockManager::isBlocked).take(500)
    if (candidates.size < 2 && saved.isEmpty()) return

    Column(Modifier.padding(horizontal = 24.dp)) {
        Text(str("warmup_title"), style = MaterialTheme.typography.titleLarge)
        Text(str("warmup_description"), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ScrollableLazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(WarmupQueue.Direction.entries.size) { index ->
                val direction = WarmupQueue.Direction.entries[index]
                val title = str(if (direction == WarmupQueue.Direction.RISING) "warmup_rising" else "warmup_falling")
                TextButton(enabled = !building && candidates.size >= 2, onClick = {
                    building = true
                    failure = false
                    val source = candidates
                    scope.launch {
                        try {
                            val id = withContext(Dispatchers.IO) {
                                val candidates = source.map { track ->
                                    val info = AppDatabase.beatInfoDao.getBeatInfo(track.id.toString())
                                    WarmupQueue.Candidate(track, info?.energy, info?.bpm)
                                }
                                val tracks = WarmupQueue.build(candidates, direction)
                                val id = GeneratedPlaylists.create(title, tracks)
                                prefs.putString("ids", (savedIds + id).toList().takeLast(20).joinToString(","))
                                id
                            }
                            player.navigateToPlaylistId = "local_playlist:$id"
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failure = true; e.printStackTrace() }
                        finally { building = false }
                    }
                }) { Text(title) }
            }
        }
        if (building) CircularProgressIndicator()
        if (failure) Text(str("warmup_failed"), color = MaterialTheme.colorScheme.error)
        if (saved.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ScrollableLazyRow(contentPadding = PaddingValues(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(saved.size) { index ->
                    val playlist = saved[index]
                    MediaCard(playlist.title, str("warmup_track_count", playlist.trackCount), playlist.artworkUrl, false) {
                        player.navigateToPlaylistId = "local_playlist:${playlist.id}"
                    }
                }
            }
        }
    }
}
