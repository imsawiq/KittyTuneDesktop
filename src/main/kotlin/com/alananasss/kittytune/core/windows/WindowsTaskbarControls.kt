package com.alananasss.kittytune.core.windows

import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.Window
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import javax.swing.Timer

data class TaskbarLabels(val previous: String, val play: String, val pause: String, val next: String)

/** Three real controls underneath this JVM window's taskbar thumbnail, independent of the SMTC flyout. */
class WindowsTaskbarControls private constructor(
    internal val hwnd: WinDef.HWND,
    private val previous: () -> Unit,
    private val playPause: () -> Unit,
    private val next: () -> Unit,
) : Closeable {
    private data class State(val playing: Boolean, val available: Boolean, val labels: TaskbarLabels)
    private var state = State(false, false, TaskbarLabels("Previous", "Play", "Pause", "Next"))
    private var oldProcedure: Pointer? = null
    private var added = false
    private var installed = false
    @Volatile private var closed = false
    private var attempts = 0
    private val retry = Timer(250) { if (added || closed || attempts++ >= 20) retryStop() else refresh() }
    private val icons = mutableMapOf<Pair<TaskbarAction, Boolean>, WinDef.HICON>()
    private var iconSize = 0
    private val createdMessage = User32.INSTANCE.RegisterWindowMessage("TaskbarButtonCreated")

    // AWT has a separate native message thread. SetWindowLongPtr is valid for our own process;
    // SetWindowSubclass would be invalid across that thread boundary.
    private val windowProcedure = WinUser.WindowProc { window, message, wParam, lParam ->
        val action = if (message == WM_COMMAND && !closed) TaskbarAction.fromCommand(wParam.toLong()) else null
        if (action != null) {
            SwingUtilities.invokeLater {
                if (!closed && state.available) when (action) {
                    TaskbarAction.PREVIOUS -> previous()
                    TaskbarAction.PLAY_PAUSE -> playPause()
                    TaskbarAction.NEXT -> next()
                }
            }
            WinDef.LRESULT(0)
        } else {
            if (!closed && message == createdMessage) SwingUtilities.invokeLater {
                if (!closed) { added = false; attempts = 0; refresh(); retry.start() }
            }
            if (!closed && message == WM_DPICHANGED) SwingUtilities.invokeLater { if (!closed) refresh() }
            val result = oldProcedure?.let { User32.INSTANCE.CallWindowProc(it, window, message, wParam, lParam) }
                ?: User32.INSTANCE.DefWindowProc(window, message, wParam, lParam)
            if (message == WM_NCDESTROY) {
                closed = true
                SwingUtilities.invokeLater { retryStop(); disposeIcons(); retainedCallbacks.remove(this) }
            }
            result
        }
    }
    private val procedurePointer get() = CallbackReference.getFunctionPointer(windowProcedure)

    private fun getProcedure(): Pointer = if (Native.POINTER_SIZE == 8)
        User32.INSTANCE.GetWindowLongPtr(hwnd, GWLP_WNDPROC).toPointer()
    else Pointer.createConstant(Integer.toUnsignedLong(User32.INSTANCE.GetWindowLong(hwnd, GWLP_WNDPROC)))

    private fun setProcedure(pointer: Pointer): Pointer? = if (Native.POINTER_SIZE == 8)
        User32.INSTANCE.SetWindowLongPtr(hwnd, GWLP_WNDPROC, pointer)
    else Pointer.createConstant(Integer.toUnsignedLong(User32.INSTANCE.SetWindowLong(hwnd, GWLP_WNDPROC, Pointer.nativeValue(pointer).toInt())))

    private fun install() {
        check(ownsWindow(hwnd)) { "Taskbar window must belong to this JVM" }
        oldProcedure = getProcedure()
        Native.setLastError(0)
        val original = setProcedure(procedurePointer)
        check(original != null || Native.getLastError() == 0) { "Cannot install taskbar callback: ${Native.getLastError()}" }
        if (original != null) oldProcedure = original
        installed = true
        refresh()
        retry.start()
    }

    fun update(playing: Boolean, available: Boolean, labels: TaskbarLabels) = onEdt {
        val updated = State(playing, available, labels)
        if (!closed && state != updated) {
            state = updated
            refresh()
        }
    }

    private fun refresh() {
        if (closed || !ownsWindow(hwnd)) return
        try {
            ensureIcons()
            val buttons = ThumbButton().toArray(3).map { it as ThumbButton }
            TaskbarAction.entries.forEachIndexed { index, action ->
                val button = buttons[index]
                button.iId = action.buttonId
                button.hIcon = icons.getValue(action to (action == TaskbarAction.PLAY_PAUSE && state.playing)).pointer
                val tip = when (action) {
                    TaskbarAction.PREVIOUS -> state.labels.previous
                    TaskbarAction.PLAY_PAUSE -> if (state.playing) state.labels.pause else state.labels.play
                    TaskbarAction.NEXT -> state.labels.next
                }
                tip.take(259).toCharArray().copyInto(button.szTip)
                button.dwFlags = if (state.available) 0 else 1 // THBF_DISABLED
                button.write()
            }
            withTaskbar { taskbar ->
                val result = taskbar.buttons(hwnd, buttons.size, buttons.first().pointer, update = added)
                if (result >= 0) added = true
            }
        } catch (error: Exception) {
            if (attempts == 0) println("Windows taskbar controls unavailable: ${error.message}")
        }
    }

    private fun ensureIcons() {
        val desired = runCatching {
            val api = TaskbarIconApi.instance
            api.GetSystemMetricsForDpi(11, api.GetDpiForWindow(hwnd))
        }.getOrDefault(User32.INSTANCE.GetSystemMetrics(11)).coerceIn(16, 128)
        if (iconSize == desired && icons.isNotEmpty()) return
        disposeIcons()
        TaskbarAction.entries.forEach { action ->
            val variants = if (action == TaskbarAction.PLAY_PAUSE) listOf(false, true) else listOf(false)
            variants.forEach { playing ->
                val bytes = taskbarIconResource(desired, action, playing)
                val icon = TaskbarIconApi.instance.CreateIconFromResourceEx(bytes, bytes.size, true, 0x30000, desired, desired, 0)
                icons[action to playing] = requireNotNull(icon) { "Cannot create taskbar icon" }
            }
        }
        iconSize = desired
    }

    private fun disposeIcons() { icons.values.forEach { User32.INSTANCE.DestroyIcon(it) }; icons.clear(); iconSize = 0 }
    private fun retryStop() { retry.stop() }

    override fun close() = onEdt {
        if (closed) return@onEdt
        if (added && ownsWindow(hwnd)) runCatching {
            val buttons = ThumbButton().toArray(3).map { it as ThumbButton }
            TaskbarAction.entries.forEachIndexed { index, action ->
                buttons[index].apply { iId = action.buttonId; dwMask = 8; dwFlags = 8; write() }
            }
            withTaskbar { it.buttons(hwnd, 3, buttons.first().pointer, update = true) }
        }
        closed = true
        retryStop()
        if (installed && ownsWindow(hwnd)) {
            val current = getProcedure()
            if (current == procedurePointer) oldProcedure?.let { setProcedure(it) }
            if (getProcedure() != oldProcedure) retainedCallbacks.add(this) // Another hook may still forward to us until WM_NCDESTROY.
        }
        disposeIcons()
    }

    companion object {
        private const val GWLP_WNDPROC = -4
        private const val WM_COMMAND = 0x111
        private const val WM_DPICHANGED = 0x2e0
        private const val WM_NCDESTROY = 0x82
        private val retainedCallbacks = ConcurrentHashMap.newKeySet<WindowsTaskbarControls>()
        private val classId = Guid.GUID("{56FDF344-FD6D-11d0-958A-006097C9A090}")
        private val interfaceId = Guid.GUID("{EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF}")

        internal fun ownsWindow(hwnd: WinDef.HWND): Boolean {
            if (!User32.INSTANCE.IsWindow(hwnd)) return false
            val pid = IntByReference()
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid)
            return Integer.toUnsignedLong(pid.value) == ProcessHandle.current().pid()
        }

        fun attach(window: Window, previous: () -> Unit, playPause: () -> Unit, next: () -> Unit): WindowsTaskbarControls? {
            if (!System.getProperty("os.name").lowercase().contains("win") || !window.isDisplayable) return null
            val attached = AtomicReference<WindowsTaskbarControls?>()
            onEdt {
                val controls = WindowsTaskbarControls(WinDef.HWND(Native.getWindowPointer(window)), previous, playPause, next)
                try { controls.install(); attached.set(controls) }
                catch (error: Exception) { controls.close(); println("Windows taskbar hook unavailable: ${error.message}") }
            }
            return attached.get()
        }

        private fun onEdt(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }

        private fun withTaskbar(action: (TaskbarList3) -> Unit) {
            val initialized = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED).toInt()
            try {
                val pointer = PointerByReference()
                val created = Ole32.INSTANCE.CoCreateInstance(classId, Pointer.NULL, WTypes.CLSCTX_INPROC_SERVER, interfaceId, pointer)
                if (created.toInt() < 0 || pointer.value == null) return
                val taskbar = TaskbarList3(pointer.value)
                try { if (taskbar.initialize() >= 0) action(taskbar) } finally { taskbar.Release() }
            } finally {
                if (initialized == 0 || initialized == 1) Ole32.INSTANCE.CoUninitialize()
            }
        }
    }
}
