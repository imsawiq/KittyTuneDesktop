package com.alananasss.kittytune.data.together

/** Orders one peer's messages using that peer's clock, never comparing different computers' clocks. */
internal class TogetherPeerTimeline(
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxPeers: Int = 512,
) {
    private data class Peer(
        var activityAt: Long? = null,
        var presenceAt: Long? = null,
        var departedAt: Long? = null,
        var stateAt: Long? = null,
        var stateReceivedAt: Long = 0L,
        var activityReceivedAt: Long = 0L,
    )

    init { require(maxPeers > 0) }

    private val peers = LinkedHashMap<Pair<String, String>, Peer>()

    @Synchronized
    fun acceptHello(code: String, sender: String, sentAt: Long): Boolean {
        val now = monotonicMs()
        val key = code to sender
        val peer = peerAt(key, now)
        if (peer.presenceAt?.let { sentAt < it } == true ||
            peer.departedAt?.let { sentAt <= it } == true) return false
        peer.presenceAt = sentAt
        peer.activityAt = maxOf(peer.activityAt ?: sentAt, sentAt)
        recordActivity(key, peer, now)
        return true
    }

    @Synchronized
    fun acceptDeparture(code: String, sender: String, sentAt: Long): Boolean {
        val now = monotonicMs()
        val key = code to sender
        val peer = peerAt(key, now)
        if (peer.activityAt?.let { sentAt < it } == true) return false
        peer.activityAt = sentAt
        peer.presenceAt = sentAt
        peer.departedAt = sentAt
        recordActivity(key, peer, now)
        return true
    }

    @Synchronized
    fun acceptHostActivity(code: String, sender: String, sentAt: Long): Boolean {
        val now = monotonicMs()
        val key = code to sender
        val peer = peerAt(key, now)
        if (peer.departedAt?.let { sentAt <= it } == true) return false
        peer.activityAt = maxOf(peer.activityAt ?: sentAt, sentAt)
        recordActivity(key, peer, now)
        return true
    }

    @Synchronized
    fun acceptState(code: String, sender: String, sentAt: Long): Boolean {
        val now = monotonicMs()
        val key = code to sender
        var peer = peerAt(key, now)
        if (peer.departedAt?.let { sentAt <= it } == true) return false
        if (peer.stateAt?.let { sentAt < it } == true) {
            if (now - peer.stateReceivedAt < IDLE_TIMEOUT_MS) return false
            // A restarted sender can retain its member id while its wall clock moves backwards.
            // Only a full minute without playback snapshots permits resetting that clock.
            peer = Peer()
        }
        peer.stateAt = sentAt
        peer.stateReceivedAt = now
        peer.activityAt = maxOf(peer.activityAt ?: sentAt, sentAt)
        recordActivity(key, peer, now)
        return true
    }

    private fun peerAt(key: Pair<String, String>, now: Long): Peer {
        val peer = peers[key]
        return if (peer != null && now - peer.activityReceivedAt < IDLE_TIMEOUT_MS) peer else {
            peers.remove(key)
            Peer()
        }
    }

    private fun recordActivity(key: Pair<String, String>, peer: Peer, now: Long) {
        peer.activityReceivedAt = now
        peers.remove(key)
        peers[key] = peer
        if (peers.size > maxPeers) peers.entries.iterator().run { next(); remove() }
    }

    @Synchronized
    fun forget(code: String) {
        peers.keys.removeAll { it.first == code }
    }

    @Synchronized
    fun clear() = peers.clear()

    private companion object {
        const val IDLE_TIMEOUT_MS = 60_000L
    }
}
