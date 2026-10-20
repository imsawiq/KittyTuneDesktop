package com.alananasss.kittytune.data

import com.alananasss.kittytune.data.discord.DiscordPresenceTransport
import com.alananasss.kittytune.data.local.DiscordStatusDisplay
import com.alananasss.kittytune.domain.Track
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordRpcLifecycleTest {
    @Test fun `clearing idle presence never connects but a subsequent track does`() {
        val dispatcher = QueuedDispatcher()
        val job = SupervisorJob()
        var connectAttempts = 0
        val rpc = DiscordRPC(
            CoroutineScope(job + dispatcher),
            connect = { connectAttempts++; null },
            statusDisplay = { DiscordStatusDisplay.SONG },
        )
        try {
            rpc.clearPresence()
            dispatcher.runPending()
            assertEquals(0, connectAttempts)

            // Prove that the worker ran and can connect, rather than merely staying queued.
            runBlocking {
                rpc.updatePresence(Track(1, "A track", null, 180_000, null), null, true, 0)
            }
            dispatcher.runPending()
            assertEquals(1, connectAttempts)
        } finally {
            rpc.closeRPC()
            dispatcher.runPending()
            assertTrue(job.isCompleted)
        }
    }

    @Test fun `disconnect during handshake closes late transport without publishing or restoring connected status`() {
        val fixture = ConnectingSession()
        try {
            fixture.publish("Old track")
            fixture.awaitConnecting()
            fixture.rpc.closeRPC()
            fixture.finishConnecting()
            fixture.awaitWorkerStopped()

            assertFalse(fixture.transport.isOpen)
            assertTrue(fixture.transport.commands.isEmpty())
            assertEquals(DiscordRPC.Status.DISABLED, DiscordRPC.status.value)
        } finally { fixture.close() }
    }

    @Test fun `track changed during handshake publishes latest metadata first`() {
        val fixture = ConnectingSession()
        try {
            fixture.publish("Old track")
            fixture.awaitConnecting()
            fixture.publish("New track")
            fixture.finishConnecting()
            fixture.transport.awaitCommand()

            val activity = JsonParser.parseString(fixture.transport.commands.single())
                .asJsonObject["args"].asJsonObject["activity"].asJsonObject
            assertEquals("New track", activity["details"].asString)
        } finally { fixture.close() }
    }

    @Test fun `clear requested during handshake never briefly restores previous track`() {
        val fixture = ConnectingSession()
        try {
            fixture.publish("Old track")
            fixture.awaitConnecting()
            fixture.rpc.clearPresence()
            fixture.finishConnecting()
            fixture.transport.awaitCommand()

            val activity = JsonParser.parseString(fixture.transport.commands.single())
                .asJsonObject["args"].asJsonObject["activity"]
            assertTrue(activity.isJsonNull)
        } finally { fixture.close() }
    }

    private class ConnectingSession : AutoCloseable {
        private val job = SupervisorJob()
        private val connecting = CountDownLatch(1)
        private val allowConnection = CountDownLatch(1)
        val transport = RecordingTransport()
        val rpc = DiscordRPC(
            CoroutineScope(job + Dispatchers.IO),
            connect = {
                connecting.countDown()
                check(allowConnection.await(5, TimeUnit.SECONDS)) { "Test did not release handshake" }
                transport
            },
            statusDisplay = { DiscordStatusDisplay.SONG },
        )

        fun publish(title: String) = runBlocking {
            rpc.updatePresence(Track(1, title, null, 180_000, null), "Playlist", true, 0)
        }

        fun awaitConnecting() = assertTrue(connecting.await(5, TimeUnit.SECONDS))
        fun finishConnecting() = allowConnection.countDown()
        fun awaitWorkerStopped() = runBlocking { withTimeout(5_000) { job.join() } }

        override fun close() {
            finishConnecting()
            rpc.closeRPC()
            awaitWorkerStopped()
        }
    }

    private class RecordingTransport : DiscordPresenceTransport {
        @Volatile override var isOpen = true
            private set
        val commands = CopyOnWriteArrayList<String>()
        private val commandReceived = CountDownLatch(1)

        override fun send(command: String) {
            check(isOpen)
            commands += command
            commandReceived.countDown()
        }

        fun awaitCommand() = assertTrue(commandReceived.await(5, TimeUnit.SECONDS))
        override fun close() { isOpen = false }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pending.add(block)
        }

        fun runPending() {
            var executed = 0
            while (pending.isNotEmpty()) {
                check(++executed <= 100) { "Coroutine worker did not become idle" }
                pending.remove().run()
            }
        }
    }
}
