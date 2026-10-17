package com.alananasss.kittytune.ui.player.lyrics

import com.alananasss.kittytune.ui.player.lyrics.timing.rememberSmoothPosition

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.alananasss.kittytune.ui.player.PlayerViewModel

/** The current line under the full-player cover, using the same word reveal as the lyrics page. */
@Composable
internal fun CurrentLyricText(vm: PlayerViewModel, color: Color, modifier: Modifier = Modifier) {
    val plain = vm.lyricsLines.isEmpty()
    val plainText = vm.rawPlainLyrics.orEmpty()
    val speed = vm.effectivePlainAutoScrollSpeed
    val lines = if (plain) remember(plainText, speed) { pacedPlainLyrics(plainText, speed) } else vm.lyricsLines
    val position = rememberSmoothPosition({ vm.uiCurrentPosition }, vm.uiIsPlaying, vm.effectsState.speed)
    val offset = if (plain) 0L else vm.lyricsOffset
    val index by remember(lines, offset) {
        derivedStateOf { LyricsUtils.activeLineIndex(lines, (position.value + offset).toLong()) }
    }
    AnimatedContent(
        targetState = lines.getOrNull(index),
        transitionSpec = {
            (fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 2 }) togetherWith
                (fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 2 })
        },
        label = "currentLyricLine",
        modifier = modifier,
    ) { source ->
        val line = remember(source) { source?.withRevealedWords() }
        if (line != null) {
            val style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            LyricLineText(
                line = line,
                isActive = true,
                positionMs = { position.value + offset },
                wordSync = vm.isWordSyncEnabled || source?.words.isNullOrEmpty(),
                fillEffect = vm.isAppleMusicEffectEnabled || source?.words.isNullOrEmpty(),
                activeStyle = style,
                inactiveStyle = style,
                activeColor = color,
                inactiveColor = color.copy(alpha = 0.6f),
                unsungColor = color.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
            )
        }
    }
}
