package com.alananasss.kittytune.ui.common

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alananasss.kittytune.core.str
import com.alananasss.kittytune.data.DownloadManager
import com.alananasss.kittytune.data.download.DownloadAudioFormat
import com.alananasss.kittytune.data.download.LocalAudioFormatCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val localAudioFormats = LocalAudioFormatCache()

/** A local file signature confirms the format; the catalog or requested quality cannot. */
@Composable
internal fun TrackLosslessBadge(trackId: Long) {
    val storage by DownloadManager.storageTrigger.collectAsState()
    val isFlac by produceState(false, trackId, storage) {
        value = false
        value = withContext(Dispatchers.IO) {
            val path = DownloadManager.getLocalTrack(trackId)?.localAudioPath
            localAudioFormats.formatFor(path) == DownloadAudioFormat.FLAC
        }
    }
    if (!isFlac) return
    Spacer(Modifier.width(4.dp))
    Tip(str("audio_quality_local_flac")) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                text = "FLAC",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}
