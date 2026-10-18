package com.alananasss.kittytune.core.windows

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

@Structure.FieldOrder("dwMask", "iId", "iBitmap", "hIcon", "szTip", "dwFlags")
internal class ThumbButton : Structure() {
    @JvmField var dwMask = 0x0e // THB_ICON | THB_TOOLTIP | THB_FLAGS
    @JvmField var iId = 0
    @JvmField var iBitmap = 0
    @JvmField var hIcon: Pointer? = null
    @JvmField var szTip = CharArray(260)
    @JvmField var dwFlags = 0

    internal fun nativeOffsets() = listOf(fieldOffset("hIcon"), fieldOffset("szTip"), fieldOffset("dwFlags"))
}

/** IUnknown(0..2), ITaskbarList(3..7), ITaskbarList2(8), ITaskbarList3(9..20). */
internal class TaskbarList3(pointer: Pointer) : Unknown(pointer) {
    fun initialize(): Int = _invokeNativeInt(3, arrayOf(pointer))
    fun buttons(hwnd: WinDef.HWND, count: Int, buttons: Pointer, update: Boolean): Int =
        _invokeNativeInt(if (update) 16 else 15, arrayOf(pointer, hwnd, count, buttons))
}

internal interface TaskbarIconApi : StdCallLibrary {
    fun CreateIconFromResourceEx(bits: ByteArray, bytes: Int, isIcon: Boolean, version: Int, width: Int, height: Int, flags: Int): WinDef.HICON?
    fun GetDpiForWindow(hwnd: WinDef.HWND): Int
    fun GetSystemMetricsForDpi(index: Int, dpi: Int): Int

    companion object {
        val instance: TaskbarIconApi by lazy { Native.load("user32", TaskbarIconApi::class.java, W32APIOptions.DEFAULT_OPTIONS) }
    }
}
