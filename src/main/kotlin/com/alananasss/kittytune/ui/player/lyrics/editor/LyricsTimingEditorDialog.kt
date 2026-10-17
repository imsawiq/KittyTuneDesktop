package com.alananasss.kittytune.ui.player.lyrics.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alananasss.kittytune.core.str
import com.alananasss.kittytune.core.trackTextInput
import com.alananasss.kittytune.ui.common.escapeDismisses
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** A local draft: playback keeps running, while saving uses the established persistent LOCAL import. */
@Composable
internal fun LyricsTimingEditorDialog(
    initialText: String,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
    onSeekStart: () -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    onPublishDraft: ((plainLyrics: String, syncedLyrics: String) -> Unit)? = null,
) {
    var plain by remember { mutableStateOf(initialText) }
    var draft by remember { mutableStateOf<LyricsTimingDraft?>(null) }
    var mode by remember { mutableStateOf(LyricsTimingDraft.Mode.LINE) }
    var selected by remember { mutableIntStateOf(0) }
    val cells = remember(draft, mode) { draft?.cells(mode).orEmpty() }
    val selectedCell = cells.getOrNull(selected)
    val complete = remember(draft, mode, durationMs) { draft?.isComplete(mode, durationMs) == true }
    val active = cells.indexOfLast { (LyricsTimingDraft.parseTime(it.time) ?: Long.MAX_VALUE) <= positionMs }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth(0.94f).fillMaxHeight(0.92f).escapeDismisses(onDismiss),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(str("lyrics_editor_title"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(str("btn_close")) }
                }
                Text(str("lyrics_editor_hint"), style = MaterialTheme.typography.bodyMedium)
                if (draft == null) {
                    OutlinedTextField(
                        value = plain,
                        onValueChange = { plain = it },
                        label = { Text(str("lyrics_editor_text")) },
                        modifier = Modifier.fillMaxWidth().weight(1f).trackTextInput(),
                    )
                    Button(onClick = {
                        draft = LyricsTimingDraft.fromPlain(plain)
                        selected = 0
                    }, enabled = LyricsTimingDraft.canPrepare(plain)) { Text(str("lyrics_editor_prepare")) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LyricsTimingDraft.Mode.entries.forEach { option ->
                            FilterChip(
                                selected = mode == option,
                                onClick = { mode = option; selected = 0 },
                                label = { Text(str(if (option == LyricsTimingDraft.Mode.LINE) "lyrics_editor_line" else "lyrics_editor_word")) },
                            )
                        }
                        TextButton(onClick = { draft = null }) { Text(str("lyrics_editor_back")) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onTogglePlayback) {
                            Text(str(if (isPlaying) "action_pause" else "action_play"))
                        }
                        TextButton(onClick = onSeekStart) { Text(str("lyrics_editor_seek_start")) }
                        Text(LyricsTimingDraft.formatTime(positionMs.coerceAtLeast(0)), style = MaterialTheme.typography.titleMedium)
                    }
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        val preview = cells.getOrNull(active)?.let { cell -> draft?.lines?.getOrNull(cell.line)?.text }.orEmpty()
                        Text(preview.ifBlank { selectedCell?.text.orEmpty() }, modifier = Modifier.fillMaxWidth().padding(12.dp),
                            style = MaterialTheme.typography.titleMedium, maxLines = 3)
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(cells, key = { _, cell -> "${cell.line}:${cell.word}" }) { index, cell ->
                            Surface(
                                color = when (index) {
                                    selected -> MaterialTheme.colorScheme.primaryContainer
                                    active -> MaterialTheme.colorScheme.secondaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceContainer
                                },
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth().clickable { selected = index },
                            ) {
                                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(cell.time.ifBlank { "--:--.---" }, style = MaterialTheme.typography.labelLarge)
                                    Text(cell.text, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    selectedCell?.let { cell ->
                        OutlinedTextField(
                            value = cell.time,
                            onValueChange = { draft = draft?.withTime(cell, it) },
                            singleLine = true,
                            label = { Text(str("lyrics_editor_time")) },
                            isError = cell.time.isNotBlank() && LyricsTimingDraft.parseTime(cell.time) == null,
                            modifier = Modifier.fillMaxWidth().trackTextInput(),
                        )
                    }
                    Button(onClick = {
                        selectedCell?.let { cell ->
                            draft = draft?.withTime(cell, LyricsTimingDraft.formatTime(positionMs.coerceAtLeast(0)))
                            selected = (selected + 1).coerceAtMost(cells.lastIndex)
                        }
                    }, enabled = selectedCell != null, modifier = Modifier.fillMaxWidth()) {
                        Text(str("lyrics_editor_tap"))
                    }
                    if (!complete) Text(str("lyrics_editor_invalid"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            val lrc = requireNotNull(draft).exportLrc(mode, durationMs)
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(lrc), null)
                        }, enabled = complete) { Text(str("lyrics_copy_text")) }
                        if (onPublishDraft != null) {
                            TextButton(onClick = {
                                onPublishDraft(requireNotNull(draft).lines.joinToString("\n") { it.text },
                                    requireNotNull(draft).exportLrc(mode, durationMs))
                            }, enabled = complete) { Text(str("lyrics_editor_publish")) }
                        }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { onSave(requireNotNull(draft).exportLrc(mode, durationMs)) }, enabled = complete) {
                            Text(str("btn_save"))
                        }
                    }
                }
            }
        }
    }
}

