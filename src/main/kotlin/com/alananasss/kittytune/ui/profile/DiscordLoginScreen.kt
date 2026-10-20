package com.alananasss.kittytune.ui.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.alananasss.kittytune.core.str
import com.alananasss.kittytune.ui.common.SettingsScaffold
import com.alananasss.kittytune.ui.player.PlayerViewModel

/** Compatibility destination for saved navigation to the former login screen. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun DiscordLoginScreen(
    onBackClick: () -> Unit,
    onLoginSuccess: () -> Unit,
    playerViewModel: PlayerViewModel? = null,
) {
    SettingsScaffold(title = str("discord_status_header"), onBackClick = onBackClick) { padding ->
        Box(Modifier.padding(padding)) {
            DiscordSettingsScreen(
                onBackClick = onBackClick,
                onNavigateToLogin = {},
                playerViewModel = playerViewModel,
            )
        }
    }
}
