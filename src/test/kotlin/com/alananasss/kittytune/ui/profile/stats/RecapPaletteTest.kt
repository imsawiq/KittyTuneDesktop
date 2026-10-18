package com.alananasss.kittytune.ui.profile.stats

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecapPaletteTest {
    @Test fun `mixed dark and bright containers keep readable text everywhere on the card`() {
        val text = Color(0xFFA0A8B7)
        val result = readableRecapStops(listOf(Color(0xFF182D3D), Color(0xFFFBA579)), text)
        assertReadable(result, text)
    }

    @Test fun `both built in themes retain readable recap cards`() {
        for (theme in listOf(darkColorScheme(), lightColorScheme())) {
            for ((stops, text) in listOf(
                listOf(theme.primaryContainer, theme.tertiaryContainer) to theme.onPrimaryContainer,
                listOf(theme.secondaryContainer, theme.primaryContainer) to theme.onSecondaryContainer,
                listOf(theme.tertiaryContainer, theme.secondaryContainer) to theme.onTertiaryContainer,
                listOf(theme.surfaceContainerHighest, theme.primaryContainer) to theme.onSurface,
            )) assertReadable(readableRecapStops(stops, text), text)
        }
    }

    @Test fun `already readable colors are kept intact`() {
        val colors = listOf(Color(0xFF151515), Color(0xFF303030))
        assertEquals(colors, readableRecapStops(colors, Color.White))
    }

    private fun assertReadable(stops: List<Color>, text: Color) {
        for ((from, to) in stops.zipWithNext()) for (step in 0..200) {
            val background = lerp(from, to, step / 200f)
            val a = text.luminance() + 0.05f
            val b = background.luminance() + 0.05f
            assertTrue(maxOf(a, b) / minOf(a, b) >= 4.5f, "Unreadable gradient at $step/200")
        }
    }
}
