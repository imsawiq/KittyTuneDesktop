package com.alananasss.kittytune.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.alananasss.kittytune.core.Strings
import com.alananasss.kittytune.core.str
import com.alananasss.kittytune.data.artist.ArtistReach
import java.text.NumberFormat

/** The streaming profile for [artistName], once it has been looked up; null before that and when there is none. */
@Composable
fun rememberArtistProfile(artistName: String?): ArtistReach.Profile? =
    rememberArtistLookup(artistName, ArtistReach::profileOf)

/** A banner for [artistName] from their streaming profile, or a member's for a duo; null before it is found and when there is none. */
@Composable
fun rememberArtistBanner(artistName: String?): String? =
    rememberArtistLookup(artistName, ArtistReach::bannerOf)

/** A new artist gets fresh state immediately, before its lookup can finish. */
@Composable
internal fun <T> rememberArtistLookup(artistName: String?, resolve: suspend (String?) -> T?): T? =
    key(artistName) {
        produceState<T?>(initialValue = null, artistName) {
            value = resolve(artistName)
        }.value
    }

/** "550,512 monthly listeners", the way Spotify writes it: the whole number, grouped. */
fun monthlyListenersLabel(count: Long): String =
    str("artist_monthly_listeners", NumberFormat.getIntegerInstance(Strings.locale()).format(count))

/**
 * How many people listen to [artistName] each month, written under an artist's name wherever one is shown
 * (issue #66). Takes no room until the number is known, and none at all for an artist with no such profile.
 */
@Composable
fun MonthlyListenersText(
    artistName: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = LocalContentColor.current,
) {
    val listeners = rememberArtistProfile(artistName)?.monthlyListeners
    AnimatedVisibility(visible = listeners != null, enter = fadeIn() + expandVertically(), modifier = modifier) {
        val label = listeners?.let { "Spotify · ${monthlyListenersLabel(it)}" }.orEmpty()
        Tip(label) {
            Text(
                text = label,
                style = style,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
