package com.alananasss.kittytune.core.windows

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFrame
import javax.swing.SwingUtilities
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsTaskbarNativeTest {
    @Test
    fun `Windows thumbnail structure has the SDK pointer alignment and WCHAR buffer layout`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("win"))
        val button = ThumbButton()
        assertEquals(if (Native.POINTER_SIZE == 8) 552 else 540, button.size())
        assertEquals(if (Native.POINTER_SIZE == 8) listOf(16, 24, 544) else listOf(12, 16, 536), button.nativeOffsets())
    }

    @Test(timeout = 20_000)
    fun `real HWND thumbnail commands reach EDT and disposal restores the original procedure`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("win") && !GraphicsEnvironment.isHeadless())
        lateinit var window: JFrame
        SwingUtilities.invokeAndWait { window = JFrame("KittyTune thumbnail test").apply { setSize(240, 120); isVisible = true } }
        val hwnd = WinDef.HWND(Native.getWindowPointer(window))
        val original = User32.INSTANCE.GetWindowLongPtr(hwnd, -4).toPointer()
        val onEdt = AtomicBoolean(true)
        val count = AtomicInteger()
        val completed = CountDownLatch(3)
        val callback = {
            onEdt.set(onEdt.get() && SwingUtilities.isEventDispatchThread())
            count.incrementAndGet()
            completed.countDown()
        }
        val controls = WindowsTaskbarControls.attach(window, callback, callback, callback)
        try {
            assertNotNull(controls)
            assertTrue(WindowsTaskbarControls.ownsWindow(hwnd))
            controls.update(false, true, TaskbarLabels("Previous", "Play", "Pause", "Next"))
            TaskbarAction.entries.forEach { action ->
                User32.INSTANCE.PostMessage(hwnd, 0x111, WinDef.WPARAM((0x1800L shl 16) or action.buttonId.toLong()), WinDef.LPARAM(0))
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS), "All three native thumbnail commands must arrive")
            assertTrue(onEdt.get())
            controls.close()
            assertEquals(original, User32.INSTANCE.GetWindowLongPtr(hwnd, -4).toPointer())
            assertEquals(3, count.get())
        } finally {
            controls?.close()
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }
}
