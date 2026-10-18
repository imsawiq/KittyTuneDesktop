package com.alananasss.kittytune.data.local

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Test
import kotlin.test.assertSame

class AppDatabaseInitializationTest {
    @Test fun `repeated concurrent initialization retains the connection used by DAO transactions`() {
        AppDatabase.init()
        val connection = AppDatabase.raw()
        val workers = Executors.newFixedThreadPool(4)
        try {
            val calls = List(16) { Callable { AppDatabase.init(); AppDatabase.raw() } }
            for (result in workers.invokeAll(calls, 10, TimeUnit.SECONDS)) {
                assertSame(connection, result.get())
            }
        } finally {
            workers.shutdownNow()
        }
    }
}
