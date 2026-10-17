package com.alananasss.kittytune.ui.common

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.assertEquals
import org.junit.Test

class TrackMenuPositionTest {
    private fun position(x: Int, preferRight: Boolean = false) = AtPointPositionProvider(IntOffset(x, 20), preferRight)
        .calculatePosition(IntRect.Zero, IntSize(1000, 800), LayoutDirection.Ltr, IntSize(400, 500))

    @Test fun `player menu stays to the right and only clamps at window edge`() {
        assertEquals(IntOffset(500, 20), position(500, true))
        assertEquals(IntOffset(600, 20), position(800, true))
    }

    @Test fun `ordinary context menu can flip to avoid window edge`() {
        assertEquals(IntOffset(400, 20), position(800))
        assertEquals(IntOffset(100, 20), position(100))
    }
}
