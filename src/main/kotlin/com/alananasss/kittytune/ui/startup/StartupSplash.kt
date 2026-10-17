package com.alananasss.kittytune.ui.startup

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.Window
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingConstants
import javax.swing.Timer

/** Visible before native audio, the database, repositories, and the Compose player initialize. */
internal class StartupSplash private constructor(private val window: JWindow?, private val timer: Timer?) {
    fun close() {
        val dispose = Runnable {
            timer?.stop()
            window?.dispose()
        }
        if (EventQueue.isDispatchThread()) dispose.run() else EventQueue.invokeLater(dispose)
    }

    companion object {
        fun show(): StartupSplash {
            if (GraphicsEnvironment.isHeadless()) return StartupSplash(null, null)
            val result = AtomicReference<StartupSplash>()
            val create = Runnable { result.set(createWindow()) }
            if (EventQueue.isDispatchThread()) create.run() else EventQueue.invokeAndWait(create)
            return result.get()
        }

        private fun createWindow(): StartupSplash {
            val background = Color(24, 26, 32)
            val foreground = Color(224, 228, 239)
            val spinner = StartupSpinner(foreground)
            val panel = JPanel(BorderLayout(0, 20)).apply {
                this.background = background
                border = BorderFactory.createEmptyBorder(32, 36, 28, 36)
                preferredSize = Dimension(340, 250)
            }
            val logo = StartupSplash::class.java.getResourceAsStream("/icons/kittytune.png")?.use(ImageIO::read)
            panel.add(JLabel("KittyTune", SwingConstants.CENTER).apply {
                this.foreground = foreground
                font = Font(Font.DIALOG, Font.BOLD, 24)
                horizontalTextPosition = SwingConstants.CENTER
                verticalTextPosition = SwingConstants.BOTTOM
                iconTextGap = 18
                if (logo != null) icon = ImageIcon(logo.getScaledInstance(76, 76, java.awt.Image.SCALE_SMOOTH))
            }, BorderLayout.CENTER)
            panel.add(spinner, BorderLayout.SOUTH)
            val window = JWindow().apply {
                type = Window.Type.UTILITY
                focusableWindowState = false
                contentPane = panel
                pack()
                setLocationRelativeTo(null)
                isVisible = true
                // The first startup frame reaches the native window before initialization continues.
                rootPane.paintImmediately(0, 0, width, height)
            }
            val timer = Timer(16) { spinner.advance() }.apply { start() }
            return StartupSplash(window, timer)
        }
    }
}

private class StartupSpinner(private val color: Color) : JComponent() {
    private var phase = 0f

    init {
        preferredSize = Dimension(140, 5)
        accessibleContext?.accessibleName = "KittyTune"
    }

    fun advance() {
        phase = (phase + 0.018f) % 1f
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(color.red, color.green, color.blue, 35)
            g.fillRoundRect(0, 0, width, height, height, height)
            g.color = color
            val segment = (width * 0.28f).toInt()
            val x = ((width + segment) * phase).toInt() - segment
            g.fillRoundRect(x, 0, segment, height, height, height)
        } finally {
            g.dispose()
        }
    }
}
