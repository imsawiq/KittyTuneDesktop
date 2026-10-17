package com.alananasss.kittytune.data.discord

import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DiscordIpcConnectionTest {
    @Test fun `handshake receives READY and replies to heartbeat while accepting activity`() {
        val clientInput = PipedInputStream()
        val serverOutput = PipedOutputStream(clientInput)
        val serverInput = PipedInputStream()
        val clientOutput = PipedOutputStream(serverInput)
        val verified = CompletableFuture<Unit>()
        val keepOpen = CompletableFuture<Unit>()
        val connection = DiscordIpcConnection(clientInput, clientOutput, Closeable {
            clientInput.close()
            clientOutput.close()
        })
        Thread({
            try {
                val input = DataInputStream(serverInput)
                fun frame(): Pair<Int, String> {
                    val header = ByteArray(8).also { input.readFully(it) }
                    val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    val opcode = buffer.int
                    val payload = ByteArray(buffer.int).also { input.readFully(it) }
                    return opcode to payload.toString(Charsets.UTF_8)
                }
                fun send(opcode: Int, payload: String) {
                    val bytes = payload.toByteArray()
                    serverOutput.write(ByteBuffer.allocate(8 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(opcode).putInt(bytes.size).put(bytes).array())
                    serverOutput.flush()
                }
                val handshake = frame()
                assertEquals(0, handshake.first)
                assertTrue(handshake.second.contains("123456"))
                send(1, "{\"evt\":\"READY\"}")
                send(3, "heartbeat")
                val first = frame()
                val second = frame()
                assertEquals(setOf(1, 4), setOf(first.first, second.first))
                assertEquals("heartbeat", listOf(first, second).first { it.first == 4 }.second)
                verified.complete(Unit)
                keepOpen.get(5, TimeUnit.SECONDS)
            } catch (error: Throwable) { verified.completeExceptionally(error) }
            finally { serverInput.close(); serverOutput.close() }
        }, "test-discord-server").apply { isDaemon = true; start() }
        try {
            assertTrue(connection.handshake("123456"))
            connection.send(DiscordPresencePayload.command(null))
            verified.get(5, TimeUnit.SECONDS)
        } finally {
            keepOpen.complete(Unit)
            connection.close()
        }
    }
}
