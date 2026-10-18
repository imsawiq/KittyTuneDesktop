package com.alananasss.kittytune.core.windows

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class TaskbarAction(val buttonId: Int) {
    PREVIOUS(0x4B01), PLAY_PAUSE(0x4B02), NEXT(0x4B03);

    companion object {
        /** Ordinary WM_COMMAND messages must never be mistaken for thumbnail clicks. */
        fun fromCommand(wParam: Long): TaskbarAction? =
            if (((wParam ushr 16) and 0xffff) == 0x1800L) entries.firstOrNull { it.buttonId == (wParam and 0xffff).toInt() }
            else null
    }
}

/** A 32-bit RT_ICON DIB, including the alpha channel and the legacy AND mask. */
internal fun taskbarIconResource(size: Int, action: TaskbarAction, playing: Boolean = false): ByteArray {
    require(size in 16..128)
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.scale(size / 32.0, size / 32.0)
        val shape = Path2D.Float()
        fun rect(left: Float, top: Float, right: Float, bottom: Float) {
            shape.moveTo(left, top); shape.lineTo(right, top); shape.lineTo(right, bottom); shape.lineTo(left, bottom); shape.closePath()
        }
        fun triangle(left: Float, top: Float, right: Float, bottom: Float, reversed: Boolean = false) {
            shape.moveTo(if (reversed) right else left, top)
            shape.lineTo(if (reversed) left else right, (top + bottom) / 2)
            shape.lineTo(if (reversed) right else left, bottom)
            shape.closePath()
        }
        when (action) {
            TaskbarAction.PLAY_PAUSE -> if (playing) {
                rect(9f, 7f, 14f, 25f); rect(18f, 7f, 23f, 25f)
            } else triangle(10f, 6f, 25f, 26f)
            TaskbarAction.PREVIOUS -> { rect(7f, 7f, 11f, 25f); triangle(12f, 7f, 25f, 25f, reversed = true) }
            TaskbarAction.NEXT -> { triangle(7f, 7f, 20f, 25f); rect(21f, 7f, 25f, 25f) }
        }
        // Outlined light glyphs remain legible on Windows light/dark/high-contrast thumbnail surfaces.
        g.color = Color(25, 27, 33)
        g.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(shape)
        g.color = Color(240, 242, 248)
        g.fill(shape)
    } finally {
        g.dispose()
    }
    val maskStride = ((size + 31) / 32) * 4
    val pixelsBytes = size * size * 4
    val buffer = ByteBuffer.allocate(40 + pixelsBytes + maskStride * size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(40).putInt(size).putInt(size * 2)
    buffer.putShort(1).putShort(32).putInt(0).putInt(pixelsBytes)
    repeat(4) { buffer.putInt(0) }
    for (y in size - 1 downTo 0) for (x in 0 until size) buffer.putInt(image.getRGB(x, y))
    val maskStart = 40 + pixelsBytes
    for (row in 0 until size) for (x in 0 until size) {
        if ((image.getRGB(x, size - 1 - row) ushr 24) == 0) {
            val at = maskStart + row * maskStride + x / 8
            buffer.put(at, (buffer.get(at).toInt() or (0x80 ushr (x % 8))).toByte())
        }
    }
    return buffer.array()
}
