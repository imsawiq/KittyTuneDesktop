package com.alananasss.kittytune.data.together

import com.alananasss.kittytune.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * One connection to the relay, carrying the sealed messages of the shared playlists this app follows (issue #66).
 *
 * MQTT over TLS to a public broker: the protocol is made for small messages to many listeners over flaky networks,
 * the client reconnects by itself, and nothing needs running on our side. The broker only ever relays ciphertext;
 * see [TogetherWire]. Brokers are tried in order, the next one when the first will not take us.
 */
internal class TogetherRelay(private val clientId: String) {

    data class Incoming(val code: String, val message: TogetherMessage)

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val incoming: SharedFlow<Incoming> = _incoming

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val codesByTopic = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var client: MqttAsyncClient? = null
    @Volatile private var enabled = true
    private val outgoing = Channel<Pair<String, TogetherMessage>>(128)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for ((code, message) in outgoing) {
                if (enabled) publish(code, message)
            }
        }
    }

    @Synchronized
    fun ensureConnected() {
        if (!enabled) return
        if (client?.isConnected == true) return
        for (broker in BROKERS) {
            val attempt = runCatching { connect(broker) }
            if (attempt.isSuccess) return
            Logger.w("TogetherRelay", "Could not reach $broker: ${attempt.exceptionOrNull()?.message}")
        }
    }

    private fun connect(broker: String) {
        val mqtt = MqttAsyncClient(broker, clientId, MemoryPersistence())
        mqtt.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                _isConnected.value = true
                // A reconnect starts with no subscriptions: take them all again.
                codesByTopic.keys.forEach { topic -> runCatching { mqtt.subscribe(topic, 0) } }
            }

            override fun connectionLost(cause: Throwable?) {
                _isConnected.value = false
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                val code = codesByTopic[topic] ?: return
                val opened = TogetherWire.open(code, message.payload) ?: return
                _incoming.tryEmit(Incoming(code, opened))
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 30
        }
        try {
            mqtt.connect(options).waitForCompletion(CONNECT_TIMEOUT_MS)
        } catch (error: Exception) {
            runCatching { mqtt.close(true) }
            throw error
        }
        client?.let { previous ->
            runCatching { previous.disconnectForcibly() }
            runCatching { previous.close(true) }
        }
        client = mqtt
        _isConnected.value = true
    }

    fun follow(code: String) {
        enabled = true
        val topic = TogetherWire.topicFor(code)
        codesByTopic[topic] = TogetherWire.normalizeCode(code)
        ensureConnected()
        runCatching { client?.subscribe(topic, 0) }
    }

    fun unfollow(code: String) {
        val topic = TogetherWire.topicFor(code)
        codesByTopic.remove(topic)
        runCatching { client?.unsubscribe(topic) }
    }

    fun send(code: String, message: TogetherMessage) {
        // Keep slow broker connection attempts off Compose/player callbacks, preserving send order.
        if (outgoing.trySend(code to message).isFailure) Logger.w("TogetherRelay", "Outgoing queue is full")
    }

    private fun publish(code: String, message: TogetherMessage) {
        ensureConnected()
        val mqtt = client ?: return
        runCatching {
            mqtt.publish(TogetherWire.topicFor(code), MqttMessage(TogetherWire.seal(code, message)).apply { qos = 0 })
        }.onFailure { Logger.w("TogetherRelay", "Send failed: ${it.message}") }
    }

    @Synchronized
    fun close() {
        enabled = false
        while (outgoing.tryReceive().isSuccess) { /* discard messages from the previous session */ }
        runCatching { client?.disconnect()?.waitForCompletion(2_000) }
        runCatching { client?.close() }
        client = null
        _isConnected.value = false
    }

    private companion object {
        val BROKERS = listOf("ssl://broker.emqx.io:8883", "ssl://broker.hivemq.com:8883")
        const val CONNECT_TIMEOUT_MS = 12_000L
    }
}
