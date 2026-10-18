package com.alananasss.kittytune.ui.profile.stats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.alananasss.kittytune.data.artist.ArtistReach
import com.alananasss.kittytune.data.artist.portrait.ArtistPortraitCutouts
import com.alananasss.kittytune.domain.getHighResAvatarUrl
import com.alananasss.kittytune.ui.common.rememberDefaultAvatarPainter
import java.io.File

private data class PortraitImage(val photo: String?, val cutout: File? = null)

/** Only artist photos enter segmentation; an album cover remains an ordinary fallback. */
@Composable
internal fun ArtistPortrait(
    name: String,
    avatarUrl: String?,
    fallbackUrl: String?,
    source: String?,
    artistId: Long?,
    modifier: Modifier = Modifier,
) {
    key(name, avatarUrl, source, artistId) {
        val portrait by produceState(PortraitImage(avatarUrl.getHighResAvatarUrl())) {
            val original = avatarUrl.getHighResAvatarUrl()
            val profile = if (source == "spotify" || (source == "soundcloud" && artistId != null && artistId > 0)) {
                ArtistReach.profileOf(name)
            } else null
            // Uploaders can use logos or album art as avatars. Prefer a verified artist's own photo.
            val photo = profile?.takeIf { it.isVerified }?.avatarUrl ?: original ?: profile?.avatarUrl
            value = PortraitImage(photo)
            value = PortraitImage(photo, photo?.let { ArtistPortraitCutouts.cutout(it) })
        }
        val silhouette = rememberDefaultAvatarPainter()
        Box(modifier, contentAlignment = Alignment.BottomCenter) {
            if (portrait.cutout != null || !portrait.photo.isNullOrBlank() || !fallbackUrl.isNullOrBlank()) {
                AsyncImage(
                    model = portrait.cutout ?: portrait.photo ?: fallbackUrl,
                    contentDescription = null,
                    error = silhouette,
                    contentScale = if (portrait.cutout != null) ContentScale.Fit else ContentScale.Crop,
                    alignment = Alignment.BottomCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
