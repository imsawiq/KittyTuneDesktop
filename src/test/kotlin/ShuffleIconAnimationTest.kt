import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.alananasss.kittytune.ui.common.animation.AnimatedShuffleIcon
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShuffleIconAnimationTest {
    @Test
    fun `repeated presses animate even when shuffle mode stays enabled`() {
        val interaction = MutableInteractionSource()
        val scene = ImageComposeScene(width = 100, height = 100) {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AnimatedShuffleIcon(active = true, interactionSource = interaction, modifier = Modifier.size(40.dp))
                }
            }
        }
        var time = 0L
        fun frame(): ByteArray {
            time += 16_000_000L
            return scene.render(time).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
        }
        try {
            repeat(80) { frame() }
            val rest = frame()
            repeat(2) {
                val press = PressInteraction.Press(Offset.Zero)
                assertTrue(interaction.tryEmit(press))
                repeat(5) { frame() }
                val pressed = frame()
                assertFalse(rest.contentEquals(pressed), "Each press must change the rendered arrows")
                interaction.tryEmit(PressInteraction.Release(press))
                repeat(80) { frame() }
            }
        } finally {
            scene.close()
        }
    }
}
