package com.alananasss.kittytune.core.windows

import com.sun.jna.Platform
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertTrue

class WindowsHeapReclaimerTest {
    @Test
    fun `native heap optimization accepts the versioned information buffer`() {
        assumeTrue(Platform.isWindows())
        // Exercises the real native ABI in this test JVM; no user process or files are touched.
        assertTrue(WindowsHeapReclaimer.releaseUnusedPages())
    }
}
