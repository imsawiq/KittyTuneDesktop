package com.alananasss.kittytune.data.sync

import org.junit.Test
import kotlin.test.assertEquals

class ConnectQueueCommandVersionTest {
    @Test
    fun `an occurrence move retains the version captured before a newer remote queue arrived`() {
        assertEquals("menu-queue", connectQueueCommandVersion("move_next", "menu-queue", "newer-queue"))
    }

    @Test
    fun `an unverified occurrence never falls back to a newer queue version`() {
        assertEquals("", connectQueueCommandVersion("move_next", "", "newer-queue"))
    }

    @Test
    fun `existing queue commands retain their default version selection`() {
        listOf("queue", "remove", "move").forEach {
            assertEquals("latest", connectQueueCommandVersion(it, null, "latest"))
        }
        assertEquals("", connectQueueCommandVersion("menu_next", null, "latest"))
        assertEquals("", connectQueueCommandVersion("seek", "captured", "latest"))
    }
}
