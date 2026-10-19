package com.alananasss.kittytune.data.cover.depth

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DepthArtworkBoundsTest {
    @Test fun `invalid local and credential bearing artwork URLs never start downloads or inference`() = runBlocking {
        for (url in listOf("", "file:///private/cover.png", "C:/music/cover.jpg", "data:image/png;base64,abc",
            "https://user:password@example.org/cover.jpg")) assertNull(DepthArtworkRepository.depthFor(url))
    }

    @Test fun `unknown length response streams cannot bypass the byte limit`() {
        val exact = DepthTransfers.LimitedInput(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)
        assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), exact.readBytes().toList())
        val oversized = DepthTransfers.LimitedInput(ByteArrayInputStream(ByteArray(4)), 3)
        assertFailsWith<IOException> { oversized.readBytes() }
        val single = DepthTransfers.LimitedInput(ByteArrayInputStream(byteArrayOf(1, 2)), 1)
        assertEquals(1, single.read())
        assertFailsWith<IOException> { single.read() }
    }
}
