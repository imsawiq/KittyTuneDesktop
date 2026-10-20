package com.alananasss.kittytune.core.windows

import com.alananasss.kittytune.utils.Logger
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.BaseTSD.SIZE_T
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Returns unused native allocator pages, without paging out live audio or graphics buffers. */
internal object WindowsHeapReclaimer {
    private interface HeapApi : StdCallLibrary {
        fun HeapSetInformation(heap: Pointer?, informationClass: Int, information: Pointer, length: SIZE_T): Boolean
    }

    private val api by lazy { Native.load("kernel32", HeapApi::class.java) }
    private val worker by lazy {
        Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "Windows-Heap-Reclaim").apply { isDaemon = true } }
    }
    private val pending = AtomicBoolean(false)
    private val reportedFailure = AtomicBoolean(false)
    @Volatile private var available = true

    /** Coalesces periodic cleanup and decoder releases into at most one pending operation. */
    fun request() {
        if (!Platform.isWindows() || !available || !pending.compareAndSet(false, true)) return
        // Hidden-window cleanup requests a concurrent GC first; let its native cleaners finish.
        worker.schedule({
            try {
                if (!releaseUnusedPages() && reportedFailure.compareAndSet(false, true)) {
                    Logger.w("WindowsHeapReclaimer", "Heap optimization unavailable: error ${Native.getLastError()}")
                }
            } catch (failure: LinkageError) {
                available = false
                Logger.w("WindowsHeapReclaimer", "Heap optimization unavailable", failure)
            } catch (failure: RuntimeException) {
                available = false
                Logger.w("WindowsHeapReclaimer", "Heap optimization failed", failure)
            } finally {
                pending.set(false)
            }
        }, 1, TimeUnit.SECONDS)
    }

    // https://learn.microsoft.com/windows/win32/api/heapapi/nf-heapapi-heapsetinformation
    // HEAP_OPTIMIZE_RESOURCES_INFORMATION is two DWORDs: Version=1, Flags=0.
    internal fun releaseUnusedPages(): Boolean {
        if (!Platform.isWindows()) return false
        return Memory(8).use { information ->
            information.clear()
            information.setInt(0, 1)
            api.HeapSetInformation(null, 3, information, SIZE_T(information.size()))
        }
    }
}
