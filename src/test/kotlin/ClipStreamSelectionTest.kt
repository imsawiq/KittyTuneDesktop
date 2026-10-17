import com.alananasss.kittytune.data.artist.ArtistClips
import com.alananasss.kittytune.data.artist.ClipStream
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClipStreamSelectionTest {
    @Test
    fun `high resolution adaptive streams remain available while duplicate muxed quality wins`() {
        val muxed = ClipStream("muxed", 360)
        val adaptive = ClipStream("adaptive", 360, "audio")
        val fullHd = ClipStream("full-hd", 1080, "audio")
        val ultraHd = ClipStream("ultra-hd", 2160, "audio")
        assertEquals(listOf(ultraHd, fullHd, muxed), ArtistClips.rankStreams(listOf(adaptive, fullHd, ultraHd, muxed)))
    }

    @Test
    fun `default chooses full hd while retaining higher qualities for manual selection`() {
        val streams = ArtistClips.rankStreams(listOf(ClipStream("4k", 2160, "audio"), ClipStream("hd", 1080, "audio"), ClipStream("low", 360)))
        assertEquals(1080, ArtistClips.defaultStream(streams)?.height)
        assertEquals(3, streams.size)
    }

    @Test
    fun `unknown or unusual available quality still has a playback fallback`() {
        assertEquals(1440, ArtistClips.defaultStream(listOf(ClipStream("2k", 1440, "audio")))?.height)
        assertNull(ArtistClips.defaultStream(emptyList()))
    }
}
