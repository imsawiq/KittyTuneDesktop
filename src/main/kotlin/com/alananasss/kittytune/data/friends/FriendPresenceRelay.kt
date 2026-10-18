package com.alananasss.kittytune.data.friends

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

internal interface FriendTransport {
    fun start(topics: Set<String>, incoming: (String, ByteArray) -> Unit)
    fun updateTopics(topics: Set<String>)
    fun publish(topic: String, bytes: ByteArray)
    fun close()
}

/** A separate connection: friend codes never grant Together or Connect commands. */
internal class FriendPresenceRelay : FriendTransport {
    @Volatile private var topics: Set<String> = emptySet()
    @Volatile private var callback: ((String, ByteArray) -> Unit)? = null
    @Volatile private var client: MqttAsyncClient? = null
    @Volatile private var generation = 0L
    private var scope: CoroutineScope? = null
    private var outgoing: Channel<Pair<String, ByteArray>>? = null

    @Synchronized override fun start(topics: Set<String>, incoming: (String, ByteArray) -> Unit) {
        this.topics = topics.toSet()
        callback = incoming
        if (scope != null) return
        val expectedGeneration = ++generation
        val activeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val queue = Channel<Pair<String, ByteArray>>(32, BufferOverflow.DROP_OLDEST)
        scope = activeScope
        outgoing = queue
        activeScope.launch {
            while (isActive && generation == expectedGeneration) {
                if (client?.isConnected != true) runCatching { connect(expectedGeneration) }
                delay(5_000)
            }
        }
        activeScope.launch {
            for ((topic, bytes) in queue) {
                if (generation != expectedGeneration) break
                // Never replay an old backlog on reconnect; the repository sends a fresh heartbeat.
                val mqtt = client?.takeIf { it.isConnected } ?: continue
                runCatching { mqtt.publish(topic, MqttMessage(bytes).apply { qos = 0; isRetained = false }) }
            }
        }
    }

    override fun updateTopics(topics: Set<String>) {
        val previous = this.topics
        this.topics = topics.toSet()
        val mqtt = client?.takeIf { it.isConnected } ?: return
        (previous - topics).forEach { runCatching { mqtt.unsubscribe(it) } }
        (topics - previous).forEach { runCatching { mqtt.subscribe(it, 0) } }
    }

    override fun publish(topic: String, bytes: ByteArray) {
        if (bytes.size <= FriendPresenceWire.MAX_BYTES) outgoing?.trySend(topic to bytes.copyOf())
    }

    private fun connect(expectedGeneration: Long) {
        for (broker in BROKERS) {
            if (generation != expectedGeneration) return
            val mqtt = MqttAsyncClient(broker, "kt-friends-" + UUID.randomUUID().toString().take(12), MemoryPersistence())
            mqtt.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    if (generation == expectedGeneration) topics.forEach { runCatching { mqtt.subscribe(it, 0) } }
                }
                override fun connectionLost(cause: Throwable?) = Unit
                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
                override fun messageArrived(topic: String, message: MqttMessage) {
                    if (generation == expectedGeneration && topic in topics && message.payload.size <= FriendPresenceWire.MAX_BYTES) {
                        callback?.invoke(topic, message.payload.copyOf())
                    }
                }
            })
            val connected = runCatching {
                mqtt.connect(MqttConnectOptions().apply {
                    isCleanSession = true; isAutomaticReconnect = false
                    connectionTimeout = 8; keepAliveInterval = 30; maxInflight = 4
                    setHttpsHostnameVerificationEnabled(true)
                }).waitForCompletion(10_000)
            }.isSuccess
            val accepted = synchronized(this) {
                if (connected && generation == expectedGeneration) {
                    val previous = client
                    client = mqtt
                    previous
                } else null
            }
            dispose(accepted)
            if (connected && generation == expectedGeneration && client === mqtt) {
                return
            }
            dispose(mqtt)
        }
    }

    @Synchronized override fun close() {
        generation++
        callback = null
        topics = emptySet()
        outgoing?.close(); outgoing = null
        val previousScope = scope
        scope = null
        val previousClient = client
        client = null
        // Shutdown is off the Compose/player thread and cannot close a later generation's socket.
        if (previousScope != null) previousScope.launch {
            try { dispose(previousClient) } finally { previousScope.cancel() }
        }
    }

    private fun dispose(mqtt: MqttAsyncClient?) {
        if (mqtt == null) return
        runCatching { if (mqtt.isConnected) mqtt.disconnectForcibly(500, 500, false) }
        runCatching { mqtt.close(true) }
    }

    private companion object {
        // Both transports reach the same cluster, so falling back cannot split friends across brokers.
        val BROKERS = listOf("ssl://broker.emqx.io:8883", "wss://broker.emqx.io:8084/mqtt")
    }
}
