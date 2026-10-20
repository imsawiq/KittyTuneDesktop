package com.alananasss.kittytune.ui.profile

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.alananasss.kittytune.core.str
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiscordLegacyDestinationTest {
    @Test fun `former login destination renders local settings and keeps back navigation`() {
        var backClicks = 0
        var loginCallbacks = 0
        val scene = ImageComposeScene(
            width = 900, height = 1000, density = Density(1f), coroutineContext = Dispatchers.Unconfined,
        ) {
            MaterialTheme {
                DiscordLoginScreen(onBackClick = { backClicks++ }, onLoginSuccess = { loginCallbacks++ })
            }
        }
        try {
            var time = 0L
            fun frames() = repeat(8) { time += 16_000_000L; scene.render(time) }
            fun descendants(node: SemanticsNode): List<SemanticsNode> =
                listOf(node) + node.children.flatMap(::descendants)
            fun nodes() = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }

            frames()
            val text = nodes().flatMap {
                it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { value -> value.text }
            }
            assertTrue(str("discord_enable_rpc") in text)
            assertTrue(str("discord_ipc_description") in text)
            assertEquals(0, loginCallbacks)

            val back = assertNotNull(nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                    it.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty() &&
                    it.config.contains(SemanticsActions.OnClick)
            })
            scene.sendPointerEvent(PointerEventType.Press, back.boundsInRoot.center,
                timeMillis = time / 1_000_000L, type = PointerType.Touch)
            frames()
            scene.sendPointerEvent(PointerEventType.Release, back.boundsInRoot.center,
                timeMillis = time / 1_000_000L, type = PointerType.Touch)
            frames()
            assertEquals(1, backClicks)
            assertEquals(0, loginCallbacks)
        } finally { scene.close() }
    }
}
