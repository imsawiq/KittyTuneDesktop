package com.alananasss.kittytune.utils

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform

/**
 * Manages off-heap C/C++ native allocator state across operating systems (glibc on Linux, Windows heap).
 *
 * Prevents native memory drift (issue #66) where glibc creates dozens of 64MB arenas across 100+
 * threads and never returns freed memory to the OS, or where JavaCPP / FFmpeg / Skiko hold uncollected
 * native buffers because the JVM heap has plenty of free headroom.
 */
object NativeMemoryManager {

    // glibc malloc.h: -2 is M_TOP_PAD, not M_TRIM_THRESHOLD.
    private const val M_ARENA_MAX = -8
    private const val M_MMAP_THRESHOLD = -3
    private const val M_TRIM_THRESHOLD = -1

    private interface LibC : Library {
        fun malloc_trim(pad: Long): Int
        fun mallopt(param: Int, value: Int): Int
    }

    private val libc: LibC? = runCatching {
        if (Platform.isLinux()) {
            Native.load("c", LibC::class.java)
        } else null
    }.getOrNull()

    /**
     * One-shot native allocator tuning at application startup.
     * Restricts glibc to 2 arenas (M_ARENA_MAX = -8), preventing multi-gigabyte virtual
     * and physical memory fragmentation across 100+ threads.
     */
    fun init() {
        if (Platform.isLinux()) {
            runCatching {
                libc?.mallopt(M_ARENA_MAX, 2)
                // Allocate large buffers (>64KB) via mmap so they unmap instantly on free.
                libc?.mallopt(M_MMAP_THRESHOLD, 65536)
                // Return a free top-of-heap chunk once it reaches 128KB.
                libc?.mallopt(M_TRIM_THRESHOLD, 131072)
            }
        }
    }

    /**
     * Releases unused native allocations; Windows heap reclamation runs on its own worker:
     * 1. Forces JavaCPP to deallocate unreferenced native pointers (FFmpeg / LiteRT).
     * 2. Runs JavaCPP Pointer.trimMemory() (malloc_trim on Linux; a no-op on Windows in JavaCPP 1.5.14).
     * 3. On Linux: explicitly invokes glibc malloc_trim(0) to release unmapped arena pages back to the kernel.
     * 4. On Windows: schedules HeapOptimizeResources for all process LFH heaps, away from playback/UI threads.
     */
    fun trim() {
        runCatching {
            org.bytedeco.javacpp.Pointer.deallocateReferences()
        }
        runCatching {
            org.bytedeco.javacpp.Pointer.trimMemory()
        }
        if (Platform.isLinux()) {
            runCatching {
                libc?.malloc_trim(0L)
            }
        }
        if (Platform.isWindows()) {
            com.alananasss.kittytune.core.windows.WindowsHeapReclaimer.request()
        }
    }
}
