package com.alananasss.kittytune.ui.player.lyrics.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alananasss.kittytune.core.str
import com.alananasss.kittytune.core.trackTextInput
import com.alananasss.kittytune.data.lyrics.publishing.LrclibLyricsPublisher
import com.alananasss.kittytune.data.lyrics.publishing.LyricsPublishDraft
import com.alananasss.kittytune.data.lyrics.publishing.LyricsPublishResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Review public metadata before sending; dismissal cancels any challenge or HTTP request. */
@Composable
internal fun LyricsPublishDialog(initial: LyricsPublishDraft, onDismiss: () -> Unit) {
    var title by remember(initial) { mutableStateOf(initial.trackName) }
    var artist by remember(initial) { mutableStateOf(initial.artistName) }
    var album by remember(initial) { mutableStateOf(initial.albumName) }
    var duration by remember(initial) { mutableStateOf(initial.durationSeconds.toString()) }
    var job by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<LyricsPublishResult?>(null) }
    val scope = rememberCoroutineScope()
    val publisher = remember { LrclibLyricsPublisher() }
    val draft = initial.copy(trackName = title, artistName = artist, albumName = album,
        durationSeconds = duration.replace(',', '.').toDoubleOrNull() ?: Double.NaN)
    val busy = job?.isActive == true
    val published = result == LyricsPublishResult.Published
    val close = { job?.cancel(); onDismiss() }

    AlertDialog(
        onDismissRequest = close,
        title = { Text(str("lyrics_publish_title")) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(str("lyrics_publish_notice"))
                OutlinedTextField(title, { title = it; result = null }, enabled = !busy && !published,
                    singleLine = true, label = { Text(str("upload_field_track_title")) },
                    modifier = Modifier.fillMaxWidth().trackTextInput())
                OutlinedTextField(artist, { artist = it; result = null }, enabled = !busy && !published,
                    singleLine = true, label = { Text(str("upload_field_artist_name")) },
                    modifier = Modifier.fillMaxWidth().trackTextInput())
                OutlinedTextField(album, { album = it; result = null }, enabled = !busy && !published,
                    singleLine = true, label = { Text(str("lyrics_publish_album")) },
                    modifier = Modifier.fillMaxWidth().trackTextInput())
                OutlinedTextField(duration, { duration = it; result = null }, enabled = !busy && !published,
                    singleLine = true, label = { Text(str("lyrics_publish_duration")) },
                    modifier = Modifier.fillMaxWidth().trackTextInput())
                result?.let { outcome ->
                    val message = when (outcome) {
                        LyricsPublishResult.Published -> str("lyrics_publish_success")
                        is LyricsPublishResult.InvalidDraft -> str("lyrics_publish_invalid")
                        is LyricsPublishResult.HttpRejected -> str("lyrics_publish_http_error", outcome.statusCode)
                        LyricsPublishResult.WorkLimitReached, LyricsPublishResult.TimedOut -> str("lyrics_publish_work_limit")
                        else -> str("lyrics_publish_error")
                    }
                    Text(message, color = if (published) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (!published) Button(
                enabled = !busy,
                onClick = {
                    result = null
                    job = scope.launch { result = publisher.publish(draft) }
                },
            ) { Text(str(if (busy) "lyrics_publish_busy" else "lyrics_publish_submit")) }
        },
        dismissButton = { TextButton(onClick = close) { Text(str(if (busy) "btn_cancel" else "btn_close")) } },
    )
}
