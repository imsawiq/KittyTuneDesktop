package com.alananasss.kittytune.core.windows

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsTaskbarPolicyTest {
    @Test
    fun `thumbnail click routing ignores ordinary window commands and unknown button ids`() {
        TaskbarAction.entries.forEach {
            assertEquals(it, TaskbarAction.fromCommand((0x1800L shl 16) or it.buttonId.toLong()))
            assertNull(TaskbarAction.fromCommand(it.buttonId.toLong()))
        }
        assertNull(TaskbarAction.fromCommand((0x1800L shl 16) or 100L))
    }

    @Test
    fun `icons are 32bit DIB resources with a double-height color and transparency mask`() {
        listOf(16, 32, 48, 96).forEach { size ->
            val bytes = taskbarIconResource(size, TaskbarAction.NEXT)
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(40, header.getInt(0))
            assertEquals(size, header.getInt(4))
            assertEquals(size * 2, header.getInt(8))
            assertEquals(1, header.getShort(12).toInt())
            assertEquals(32, header.getShort(14).toInt())
            assertEquals(40 + size * size * 4 + ((size + 31) / 32) * 4 * size, bytes.size)
            assertTrue(bytes.copyOfRange(40 + size * size * 4, bytes.size).any { it != 0.toByte() })
        }
    }

    @Test
    fun `playing status changes the central glyph while previous and next remain distinct`() {
        val play = taskbarIconResource(32, TaskbarAction.PLAY_PAUSE, playing = false)
        val pause = taskbarIconResource(32, TaskbarAction.PLAY_PAUSE, playing = true)
        assertFalse(play.contentEquals(pause))
        assertFalse(taskbarIconResource(32, TaskbarAction.PREVIOUS).contentEquals(taskbarIconResource(32, TaskbarAction.NEXT)))
    }
}
