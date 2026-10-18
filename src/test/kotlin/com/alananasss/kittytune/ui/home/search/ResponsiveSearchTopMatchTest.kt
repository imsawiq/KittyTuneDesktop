package com.alananasss.kittytune.ui.home.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.test.assertTrue

class ResponsiveSearchTopMatchTest {
    @Test fun `changing columns animates height in both directions`() {
        var narrow by mutableStateOf(false)
        var height = 0
        val scene = ImageComposeScene(width = 800, height = 600, coroutineContext = Dispatchers.Unconfined) {
            Column {
                Box(Modifier.onSizeChanged { height = it.height }) {
                    ResponsiveSearchTopMatch(
                        narrow = narrow,
                        hasTracks = true,
                        hero = { Box(Modifier.fillMaxWidth().height(80.dp)) },
                        tracks = { Box(Modifier.fillMaxWidth().height(200.dp)) },
                    )
                }
            }
        }
        var time = 0L
        fun frame(): Int {
            time += 16_000_000L
            scene.render(time).close()
            return height
        }
        try {
            repeat(40) { frame() }
            val wide = height
            narrow = true
            val expanding = List(50) { frame() }
            val stacked = height
            assertTrue(stacked > wide, "Stacked songs must remain below the hero")
            assertTrue(expanding.any { it in (wide + 1) until stacked }, "Layout must render intermediate heights")
            narrow = false
            val shrinking = List(50) { frame() }
            assertTrue(height == wide)
            assertTrue(shrinking.any { it in (wide + 1) until stacked }, "Returning to columns must also animate")
        } finally { scene.close() }
    }
}
