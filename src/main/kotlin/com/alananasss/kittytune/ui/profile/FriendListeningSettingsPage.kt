package com.alananasss.kittytune.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.alananasss.kittytune.core.str
import com.alananasss.kittytune.core.trackTextInput
import com.alananasss.kittytune.data.friends.FriendListeningRepository
import com.alananasss.kittytune.data.friends.FriendInviteResult
import kotlinx.coroutines.launch

@Composable
internal fun FriendListeningSettingsPage() {
    val settings by FriendListeningRepository.settings.collectAsState()
    val friends by FriendListeningRepository.friends.collectAsState()
    val activities by FriendListeningRepository.activities.collectAsState()
    val inviteCode by FriendListeningRepository.inviteCode.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var code by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<FriendInviteResult?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(str("friends_description"), style = MaterialTheme.typography.bodyMedium)
        FriendSwitch(str("friends_share"), settings.sharingEnabled, FriendListeningRepository::setSharingEnabled)
        FriendSwitch(str("friends_show_name"), settings.showName, FriendListeningRepository::setShowName)
        Text(str("friends_code_help"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(enabled = inviteCode != null, onClick = { inviteCode?.let { clipboard.setText(AnnotatedString(it)) } }) {
            Text(str("friends_copy_code"))
        }
        OutlinedTextField(value = code, onValueChange = { if (it.length <= 512) code = it; result = null },
            label = { Text(str("friends_enter_code")) }, singleLine = true, modifier = Modifier.fillMaxWidth().trackTextInput())
        Button(enabled = code.isNotBlank() && !adding, onClick = {
            adding = true
            val entered = code
            scope.launch {
                try {
                    result = FriendListeningRepository.addFriend(entered)
                    if (result == FriendInviteResult.ADDED && code == entered) code = ""
                } finally { adding = false }
            }
        }) { Text(str("friends_add")) }
        result?.let {
            Text(str(when (it) {
                FriendInviteResult.ADDED -> "friends_added"
                FriendInviteResult.ALREADY_ADDED -> "friends_already_added"
                FriendInviteResult.INVALID_CODE -> "friends_invalid_code"
                FriendInviteResult.OWN_CODE -> "friends_own_code"
                FriendInviteResult.LIMIT_REACHED -> "friends_limit"
                FriendInviteResult.UNAVAILABLE -> "friends_unavailable"
            }), color = if (it == FriendInviteResult.ADDED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        friends.forEachIndexed { index, friend ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(activities.firstOrNull { it.friendCode == friend.code }?.displayName ?: str("friends_contact", index + 1),
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { FriendListeningRepository.removeFriend(friend.code) }) { Text(str("friends_remove")) }
            }
        }
    }
}

@Composable
private fun FriendSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
