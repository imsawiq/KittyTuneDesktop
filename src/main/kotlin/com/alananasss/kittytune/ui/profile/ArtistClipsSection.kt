package com.alananasss.kittytune.ui.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import coil3.compose.AsyncImage
import com.alananasss.kittytune.core.BackHandler
import com.alananasss.kittytune.data.artist.ArtistClips
import com.alananasss.kittytune.data.artist.Clip
import com.alananasss.kittytune.data.artist.ClipStream
import com.alananasss.kittytune.media.ClipPlayback
import com.alananasss.kittytune.ui.home.formatCompactNumber
import com.alananasss.kittytune.ui.player.PlayerViewModel
import com.alananasss.kittytune.core.str
import com.alananasss.kittytune.ui.common.pressScale

/** The artist's music videos, watched right here (tester's list, 5.5). Nothing is shown when there are none. */
@Composable
internal fun ArtistClipsSection(artistName: String, playerViewModel: PlayerViewModel) {
    val clips by produceState(emptyList<Clip>(), artistName) { value = ArtistClips.clipsFor(artistName) }
    var watching by remember { mutableStateOf<Clip?>(null) }
    if (clips.isEmpty()) return

    Column {
        ArtistSectionTitle(str("artist_clips_title"), onOpen = null)
        // Arrows at the ends, and a held left button drags it: the row is longer than the page.
        com.alananasss.kittytune.ui.common.ScrollableLazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(clips, key = { it.url }) { clip -> ClipCard(clip) { watching = clip } }
        }
    }
    watching?.let { clip -> ClipPlayerDialog(clip, playerViewModel, onDismiss = { watching = null }) }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ClipCard(clip: Clip, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val preview = remember(clip.url) { ClipPlayback(scope, mutedPreview = true) }
    val isWindowSeen = com.alananasss.kittytune.core.LocalWindowSeen.current
    var hovered by remember { mutableStateOf(false) }
    LaunchedEffect(hovered, isWindowSeen, ClipPreviews.activeUrl) {
        if (!hovered || !isWindowSeen || ClipPreviews.activeUrl != clip.url) return@LaunchedEffect
        delay(450L)
        ClipPreviews.decoder.withLock {
            try {
                withTimeoutOrNull(20_000L) {
                    val streams = ArtistClips.streams(clip)
                    val stream = streams.filter { it.height in 1..360 }.maxByOrNull { it.height }
                        ?: streams.minByOrNull { it.height }
                        ?: return@withTimeoutOrNull
                    preview.play(stream.url)
                    while (preview.isLoading) delay(40L)
                    delay(10_000L)
                }
            } finally {
                withContext(NonCancellable) { preview.stopAndJoin() }
            }
        }
    }
    DisposableEffect(preview) { onDispose { preview.stop() } }
    Column(Modifier.width(CLIP_CARD_WIDTH)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onPointerEvent(PointerEventType.Enter) { hovered = true; ClipPreviews.activeUrl = clip.url }
                .onPointerEvent(PointerEventType.Exit) { hovered = false; if (ClipPreviews.activeUrl == clip.url) ClipPreviews.activeUrl = null }
                .pressScale(interaction)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = {
                    hovered = false
                    ClipPreviews.activeUrl = null
                    preview.stop()
                    onClick()
                }),
        ) {
            AsyncImage(model = clip.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            preview.frame?.let { frame ->
                androidx.compose.foundation.Image(frame, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(
                Modifier.align(Alignment.Center).size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Text(
                formatClock(clip.durationSec * 1000),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(clip.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (clip.viewCount > 0) {
            Text(
                str("artist_clip_views", formatCompactNumber(clip.viewCount)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A clip playing over the page. The music pauses while it plays and carries on afterwards if it was playing. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ClipPlayerDialog(clip: Clip, playerViewModel: PlayerViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val playback = remember(clip) { ClipPlayback(scope) }
    var hasNoStream by remember(clip) { mutableStateOf(false) }

    var fullscreen by remember { mutableStateOf(false) }
    var streams by remember(clip) { mutableStateOf(emptyList<ClipStream>()) }
    var selectedStream by remember(clip) { mutableStateOf<ClipStream?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsHovered by remember { mutableStateOf(false) }
    var qualityMenuOpen by remember { mutableStateOf(false) }
    var lastInteraction by remember { mutableStateOf(0L) }
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(fullscreen) {
        controlsHovered = false
        entrance.snapTo(0f)
        entrance.animateTo(1f, tween(260))
    }
    LaunchedEffect(fullscreen, playback.isPaused, controlsHovered, qualityMenuOpen, lastInteraction) {
        controlsVisible = true
        if (fullscreen && !playback.isPaused && !controlsHovered && !qualityMenuOpen) {
            delay(2_800L)
            controlsVisible = false
        }
    }
    val windowBefore = remember { arrayOfNulls<java.awt.Rectangle>(1) }
    val windowRef = remember { arrayOfNulls<java.awt.Window>(1) }

    DisposableEffect(clip) {
        // Watching something else leaves a shared playlist, the way playing your own music does; otherwise the
        // pause below would pause it for everyone.
        com.alananasss.kittytune.data.together.Together.stopListening()
        val wasPlaying = playerViewModel.isPlaying
        playerViewModel.pause()
        onDispose {
            windowBefore[0]?.let { bounds -> windowRef[0]?.bounds = bounds }
            playback.stop()
            if (wasPlaying) playerViewModel.play()
        }
    }
    LaunchedEffect(clip) {
        streams = ArtistClips.streams(clip)
        selectedStream = ArtistClips.defaultStream(streams)
        val stream = selectedStream
        if (stream == null) hasNoStream = true else playback.play(stream.url, stream.audioUrl)
    }
    val selectQuality: (ClipStream) -> Unit = { stream ->
        if (stream != selectedStream) {
            selectedStream = stream
            playback.play(stream.url, stream.audioUrl, playback.positionMs, playback.isPaused)
        }
        qualityMenuOpen = false
    }
    BackHandler(onBack = { if (fullscreen) fullscreen = false else onDismiss() })
    DialogWindow(
        onCloseRequest = { if (fullscreen) fullscreen = false else onDismiss() },
        title = clip.title,
        state = rememberDialogState(width = 960.dp, height = 650.dp),
        undecorated = true,
        transparent = true,
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                if (fullscreen) fullscreen = false else onDismiss()
                true
            } else false
        },
    ) {
        // Borderless fullscreen stays on this monitor when another application gains focus.
        // Exclusive GraphicsDevice fullscreen deactivates and minimizes dialogs on Windows.
        LaunchedEffect(fullscreen, window) {
            windowRef[0] = window
            if (fullscreen) {
                windowBefore[0] = window.bounds
                window.bounds = window.graphicsConfiguration.bounds
            } else {
                windowBefore[0]?.let { window.bounds = it }
                windowBefore[0] = null
            }
        }
        Surface(
            shape = if (fullscreen) RectangleShape else RoundedCornerShape(28.dp),
            color = if (fullscreen) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = 0.7f + entrance.value * 0.3f
                scaleX = 0.985f + entrance.value * 0.015f
                scaleY = scaleX
            },
        ) {
            Column(Modifier.padding(if (fullscreen) 0.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if (fullscreen) 0.dp else 12.dp)) {
                if (!fullscreen) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(clip.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(clip.uploader, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Close, contentDescription = str("btn_close"))
                    }
                }
                Box(
                    (if (fullscreen) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)))
                        .background(Color.Black)
                        .onPointerEvent(PointerEventType.Move) { lastInteraction = System.nanoTime() }
                        .onPointerEvent(PointerEventType.Press) { lastInteraction = System.nanoTime() }
                        .pointerInput(playback.isLoading) {
                            detectTapGestures(
                                onDoubleTap = { fullscreen = !fullscreen },
                                onTap = { if (!playback.isLoading) playback.togglePause() },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val frame = playback.frame
                    if (frame != null) {
                        androidx.compose.foundation.Image(frame, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    } else if (!hasNoStream && !playback.hasFailed) {
                        AsyncImage(model = clip.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    when {
                        hasNoStream || playback.hasFailed -> Text(str("artist_clip_failed"), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                        playback.isLoading -> ContainedLoadingIndicator(Modifier.size(56.dp))
                    }
                    if (fullscreen) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = controlsVisible,
                            enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 4 },
                            exit = fadeOut(tween(260)) + slideOutVertically(tween(260)) { it / 4 },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        ) {
                            Box(
                                Modifier.fillMaxWidth()
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                                    .onPointerEvent(PointerEventType.Enter) { controlsHovered = true }
                                    .onPointerEvent(PointerEventType.Exit) { controlsHovered = false }
                                    .padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 20.dp),
                            ) {
                                MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                                    ClipControls(playback, true, streams, selectedStream, qualityMenuOpen, { qualityMenuOpen = it }, selectQuality) { fullscreen = false }
                                }
                            }
                        }
                    }
                }
                if (!fullscreen) {
                    ClipControls(playback, false, streams, selectedStream, qualityMenuOpen, { qualityMenuOpen = it }, selectQuality) { fullscreen = true }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ClipControls(
    playback: ClipPlayback,
    fullscreen: Boolean,
    streams: List<ClipStream>,
    selectedStream: ClipStream?,
    qualityMenuOpen: Boolean,
    onQualityMenuChange: (Boolean) -> Unit,
    onSelectQuality: (ClipStream) -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    var dragged by remember { mutableStateOf<Float?>(null) }
    val duration = playback.durationMs.coerceAtLeast(1L)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledIconButton(onClick = { playback.togglePause() }, enabled = !playback.isLoading, shapes = IconButtonDefaults.shapes()) {
            Icon(
                when {
                    playback.hasEnded -> Icons.Rounded.Replay
                    playback.isPaused -> Icons.Rounded.PlayArrow
                    else -> Icons.Rounded.Pause
                },
                contentDescription = null,
            )
        }
        Text(formatClock(playback.positionMs), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = dragged ?: (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { dragged = it },
            onValueChangeFinished = {
                dragged?.let { playback.seekTo((it * duration).toLong()) }
                dragged = null
            },
            enabled = playback.durationMs > 0,
            // The wheel over the bar moves the clip, five seconds a notch.
            modifier = Modifier.weight(1f).wheelSteps { notches ->
                if (playback.durationMs > 0) playback.seekTo((playback.positionMs - (notches * CLIP_WHEEL_SEEK_MS).toLong()).coerceIn(0L, playback.durationMs))
            },
        )
        Text(formatClock(playback.durationMs), style = MaterialTheme.typography.labelMedium)
        Icon(
            if (playback.volume <= 0.001f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Slider(
            value = playback.volume,
            onValueChange = { playback.volume = it },
            // And over the volume, five per cent a notch.
            modifier = Modifier.width(110.dp).wheelSteps { notches -> playback.volume = (playback.volume - notches * 0.05f).coerceIn(0f, 1f) },
        )
        Box {
            TextButton(onClick = { onQualityMenuChange(true) }, enabled = streams.isNotEmpty()) {
                Text(selectedStream?.label ?: str("artist_clip_quality"))
            }
            DropdownMenu(expanded = qualityMenuOpen, onDismissRequest = { onQualityMenuChange(false) }) {
                streams.forEach { stream ->
                    DropdownMenuItem(text = { Text(stream.label) }, onClick = { onSelectQuality(stream) })
                }
            }
        }
        IconButton(onClick = onToggleFullscreen, shapes = IconButtonDefaults.shapes()) {
            Icon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, contentDescription = null)
        }
    }
}

private const val CLIP_WHEEL_SEEK_MS = 5_000f

/** Calls [onNotches] with the wheel's delta over this element, positive down, and keeps the page from scrolling under it. */
private fun Modifier.wheelSteps(onNotches: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Scroll) continue
            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
            if (delta == 0f) continue
            onNotches(delta)
            event.changes.forEach { it.consume() }
        }
    }
}

private fun formatClock(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val hours = totalSec / 3600
    val minutes = totalSec / 60 % 60
    val seconds = totalSec % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private val CLIP_CARD_WIDTH = 260.dp

/**
 * Clips on the home page: the videos of the artists the listener plays most (round 3 of the tester's list, 24). The
 * artists come from the liked songs, the most liked first, and each one's clips are looked up once and kept.
 */
@Composable
internal fun HomeClipsShelf(
    playerViewModel: PlayerViewModel,
    clips: List<Clip> = ArtistClips.homeClips.collectAsState().value,
) {
    var watching by remember { mutableStateOf<Clip?>(null) }
    if (clips.isEmpty()) return

    Column {
        ArtistSectionTitle(str("home_clips_title"), onOpen = null)
        com.alananasss.kittytune.ui.common.ScrollableLazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            fadeColor = MaterialTheme.colorScheme.background,
        ) {
            items(clips, key = { it.url }) { clip -> ClipCard(clip) { watching = clip } }
        }
    }
    watching?.let { clip -> ClipPlayerDialog(clip, playerViewModel, onDismiss = { watching = null }) }
}


/** At most one hover decoder, including native shutdown before another preview starts. */
private object ClipPreviews {
    var activeUrl by mutableStateOf<String?>(null)
    val decoder = Mutex()
}
