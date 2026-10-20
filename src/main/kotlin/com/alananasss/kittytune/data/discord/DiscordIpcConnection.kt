package com.alananasss.kittytune.data.discord

import com.google.gson.JsonParser
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Discord's documented local RPC transport; never authenticates a user account. */
internal class DiscordIpcConnection internal constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val transport: Closeable,
) : DiscordPresenceTransport {
    @Volatile override var isOpen = true
        private set
    private val ready = CountDownLatch(1)
    private val writeLock = Any()

    internal fun handshake(applicationId: String): Boolean {
        Thread({ receiveFrames() }, "KittyTune-Discord-IPC").apply { isDaemon = true; start() }
        write(0, "{\"v\":1,\"client_id\":\"$applicationId\"}".toByteArray(Charsets.UTF_8))
        return ready.await(3, TimeUnit.SECONDS) && isOpen
    }

    override fun send(command: String) = write(1, command.toByteArray(Charsets.UTF_8))

    private fun write(opcode: Int, payload: ByteArray) {
        require(payload.size <= MAX_FRAME_SIZE)
        val frame = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(opcode).putInt(payload.size).put(payload).array()
        synchronized(writeLock) {
            check(isOpen) { "Discord IPC is closed" }
            // Discord requires one complete frame per write, including its header.
            output.write(frame)
            output.flush()
        }
    }

    private fun receiveFrames() {
        try {
            val stream = DataInputStream(input)
            val header = ByteArray(8)
            while (isOpen) {
                stream.readFully(header)
                val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val opcode = fields.int
                val length = fields.int
                require(length in 0..MAX_FRAME_SIZE) { "Invalid Discord IPC frame" }
                val payload = ByteArray(length)
                stream.readFully(payload)
                when (opcode) {
                    1 -> {
                        val event = JsonParser.parseString(payload.toString(Charsets.UTF_8)).asJsonObject
                        when (event.get("evt")?.takeUnless { it.isJsonNull }?.asString) {
                            "READY" -> ready.countDown()
                            "ERROR" -> throw java.io.IOException("Discord rejected Rich Presence")
                        }
                    }
                    2 -> return
                    3 -> write(4, payload) // Reply to the client's heartbeat.
                }
            }
        } catch (_: Exception) {
            // Disconnect is expected when Discord closes. The owner retries with backoff.
        } finally {
            close()
            ready.countDown()
        }
    }

    override fun close() {
        isOpen = false
        runCatching { transport.close() }
    }

    companion object {
        private const val MAX_FRAME_SIZE = 1024 * 1024

        fun connect(applicationId: String): DiscordIpcConnection? {
            require(applicationId.matches(Regex("[0-9]+")))
            for (index in 0..9) {
                val connection = openTransport(index) ?: continue
                try {
                    if (connection.handshake(applicationId)) return connection
                } catch (_: Exception) {
                    // A pipe may belong to another Discord instance or be closing.
                }
                connection.close()
            }
            return null
        }

        private fun openTransport(index: Int): DiscordIpcConnection? {
            if (System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) {
                return runCatching {
                    val pipe = RandomAccessFile("\\\\.\\pipe\\discord-ipc-$index", "rw")
                    val input = object : InputStream() {
                        override fun read(): Int = pipe.read()
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = pipe.read(bytes, offset, length)
                    }
                    val output = object : OutputStream() {
                        override fun write(value: Int) = pipe.write(value)
                        override fun write(bytes: ByteArray, offset: Int, length: Int) = pipe.write(bytes, offset, length)
                    }
                    DiscordIpcConnection(input, output, pipe)
                }.getOrNull()
            }
            val roots = listOfNotNull(System.getenv("XDG_RUNTIME_DIR"), System.getenv("TMPDIR"),
                System.getenv("TMP"), System.getenv("TEMP"), "/tmp").distinct()
            for (root in roots) {
                for (relative in listOf("discord-ipc-$index", "app/com.discordapp.Discord/discord-ipc-$index")) {
                    val socket = File(root, relative)
                    if (!socket.exists()) continue
                    var channel: SocketChannel? = null
                    try {
                        channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                        channel.connect(UnixDomainSocketAddress.of(socket.toPath()))
                        val socketChannel = requireNotNull(channel)
                        // Channels stream adapters share a blocking lock and deadlock a full-duplex reader.
                        val input = object : InputStream() {
                            override fun read(): Int {
                                val byte = ByteArray(1)
                                return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
                            }
                            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                                socketChannel.read(ByteBuffer.wrap(bytes, offset, length))
                        }
                        val output = object : OutputStream() {
                            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                                val buffer = ByteBuffer.wrap(bytes, offset, length)
                                while (buffer.hasRemaining()) socketChannel.write(buffer)
                            }
                        }
                        return DiscordIpcConnection(input, output, socketChannel)
                    } catch (_: Exception) {
                        runCatching { channel?.close() }
                    }
                }
            }
            return null
        }
    }
}
