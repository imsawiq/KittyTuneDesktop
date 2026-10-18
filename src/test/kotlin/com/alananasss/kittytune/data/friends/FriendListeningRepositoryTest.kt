package com.alananasss.kittytune.data.friends

import com.alananasss.kittytune.domain.Track
import com.alananasss.kittytune.domain.User
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.io.File
import com.alananasss.kittytune.core.AppDirs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FriendListeningRepositoryTest {
    private val initialTime = 1_800_000_000_000L
    private fun track() = Track(7, "Song", null, 200_000, User(3, "Artist", null), permalinkUrl = "https://soundcloud.com/artist/song")

    @Test fun `UI flow reads expose empty defaults without creating an identity or preferences file`() {
        val file = File(AppDirs.dataDir, "friend_listening_private.json")
        val before = file.takeIf { it.exists() }?.readBytes()?.toList()
        assertEquals(FriendListeningSettings(), FriendListeningRepository.settings.value)
        assertTrue(FriendListeningRepository.activities.value.isEmpty())
        assertTrue(FriendListeningRepository.friends.value.isEmpty())
        assertNull(FriendListeningRepository.inviteCode.value)
        assertEquals(before, file.takeIf { it.exists() }?.readBytes()?.toList())
    }

    @Test fun `startup and playback stay offline until explicitly sharing or adding a friend`() {
        val transport = FakeTransport()
        val engine = FriendListeningEngine(FriendIdentity.generate(), MemoryStore(), transport) { initialTime }
        try {
            engine.updatePlayback(track(), true, "Signed in account name")
            engine.start(); engine.tick()
            assertEquals(FriendListeningSettings(), engine.settings.value)
            assertEquals(0, transport.starts)
            assertTrue(transport.sent.isEmpty())
            assertTrue(engine.activities.value.isEmpty())
        } finally { engine.close() }
    }

    @Test fun `display name requires its own opt in and pause clears the listening track`() {
        val owner = FriendIdentity.generate()
        val transport = FakeTransport()
        val engine = FriendListeningEngine(owner, MemoryStore(), transport) { initialTime }
        try {
            engine.updatePlayback(track(), true, "Person")
            engine.setSharingEnabled(true); engine.start(); engine.tick()
            assertNull(open(owner, transport).displayName)
            assertNotNull(open(owner, transport).track)
            engine.setShowName(true); engine.tick()
            assertEquals("Person", open(owner, transport).displayName)
            engine.setShowName(false); engine.tick()
            assertNull(open(owner, transport).displayName)
            engine.updatePlayback(track(), false, "Person"); engine.tick()
            assertNull(open(owner, transport).track)
        } finally { engine.close() }
    }

    @Test fun `real signed activity expires without heartbeat and old signed frames cannot resurrect it`() {
        val friend = FriendIdentity.generate()
        val transport = FakeTransport()
        val store = MemoryStore()
        var now = initialTime
        val engine = FriendListeningEngine(FriendIdentity.generate(), store, transport) { now }
        val packet = FriendPresenceWire.seal(friend, FriendPresence(1, now, now + 30_000, null, FriendPublicTrack.of(track())))
        try {
            assertEquals(FriendInviteResult.ADDED, engine.addFriend(friend.invite.code))
            engine.start()
            transport.receive(friend.invite.topic, packet)
            assertEquals(1, engine.activities.value.size)
            assertNull(engine.activities.value.single().displayName)
            now += 30_000; engine.tick()
            assertTrue(engine.activities.value.isEmpty())
            transport.receive(friend.invite.topic, packet)
            assertTrue(engine.activities.value.isEmpty())
            engine.close(); engine.start()
            now = initialTime + 1_000
            transport.receive(friend.invite.topic, packet)
            assertTrue(engine.activities.value.isEmpty())
        } finally { engine.close() }
    }

    @Test fun `invite validation bounds contacts and removing a friend removes their activity`() {
        val owner = FriendIdentity.generate()
        val transport = FakeTransport()
        val engine = FriendListeningEngine(owner, MemoryStore(), transport) { initialTime }
        try {
            assertEquals(FriendInviteResult.OWN_CODE, engine.addFriend(owner.invite.code))
            assertEquals(FriendInviteResult.INVALID_CODE, engine.addFriend("not a code"))
            val friend = FriendIdentity.generate()
            assertEquals(FriendInviteResult.ADDED, engine.addFriend(friend.invite.code))
            assertEquals(FriendInviteResult.ALREADY_ADDED, engine.addFriend(friend.invite.code))
            repeat(31) { assertEquals(FriendInviteResult.ADDED, engine.addFriend(FriendIdentity.generate().invite.code)) }
            assertEquals(FriendInviteResult.LIMIT_REACHED, engine.addFriend(FriendIdentity.generate().invite.code))
            engine.start()
            transport.receive(friend.invite.topic, FriendPresenceWire.seal(friend,
                FriendPresence(1, initialTime, initialTime + 30_000, null, FriendPublicTrack.of(track()))))
            assertEquals(1, engine.activities.value.size)
            engine.removeFriend(friend.invite.code)
            assertTrue(engine.activities.value.isEmpty())
            assertEquals(31, engine.friends.value.size)
        } finally { engine.close() }
    }

    @Test fun `close can be followed by start and publication sequence survives repository recreation`() {
        val owner = FriendIdentity.generate()
        val store = MemoryStore()
        val transport = FakeTransport()
        val engine = FriendListeningEngine(owner, store, transport) { initialTime }
        engine.setSharingEnabled(true); engine.start(); engine.tick()
        val first = open(owner, transport).sequence
        engine.close(); engine.start(); engine.tick()
        assertTrue(open(owner, transport).sequence > first)
        engine.close()
        val fresh = FriendListeningEngine(owner, store, transport) { initialTime }
        try {
            fresh.start(); fresh.tick()
            assertTrue(open(owner, transport).sequence > first + 1)
        } finally { fresh.close() }
    }

    @Test fun `heartbeat retries transport failures with a cooldown and sharing off survives both publish and persistence failure`() {
        val owner = FriendIdentity.generate()
        val store = MemoryStore()
        val transport = FakeTransport()
        var now = initialTime
        val engine = FriendListeningEngine(owner, store, transport) { now }
        try {
            engine.setSharingEnabled(true)
            engine.updatePlayback(track(), true, null)
            transport.failPublish = true
            engine.start(); engine.tick()
            val failedAttempts = transport.publishAttempts
            engine.tick()
            assertEquals(failedAttempts, transport.publishAttempts)
            transport.failPublish = false
            now += 5_000; engine.tick()
            assertNotNull(FriendPresenceWire.open(owner.invite, transport.sent.last().second, now)?.track)
            transport.failPublish = true
            store.failSettings = true
            engine.setSharingEnabled(false)
            assertEquals(false, engine.settings.value.sharingEnabled)
            val afterOff = transport.publishAttempts
            now += 20_000; engine.tick()
            assertEquals(afterOff, transport.publishAttempts)
        } finally { engine.close() }
    }

    private fun open(owner: FriendIdentity, transport: FakeTransport) =
        assertNotNull(FriendPresenceWire.open(owner.invite, transport.sent.last().second, initialTime))

    private class FakeTransport : FriendTransport {
        var starts = 0
        var topics = emptySet<String>()
        private var callback: ((String, ByteArray) -> Unit)? = null
        val sent = CopyOnWriteArrayList<Pair<String, ByteArray>>()
        var failPublish = false
        var publishAttempts = 0
        override fun start(topics: Set<String>, incoming: (String, ByteArray) -> Unit) {
            starts++; this.topics = topics; callback = incoming
        }
        override fun updateTopics(topics: Set<String>) { this.topics = topics }
        override fun publish(topic: String, bytes: ByteArray) {
            publishAttempts++
            if (failPublish) throw IllegalStateException("Test transport failure")
            sent.add(topic to bytes)
        }
        override fun close() { callback = null }
        fun receive(topic: String, bytes: ByteArray) { if (topic in topics) callback?.invoke(topic, bytes) }
    }

    private class MemoryStore : FriendStore {
        private var configuration = FriendListeningSettings()
        private var contactList = emptyList<String>()
        private var sentSequence = 0L
        private var received = emptyMap<String, Long>()
        var failSettings = false
        override fun settings() = configuration
        override fun contacts() = contactList
        override fun sequence() = sentSequence
        override fun receivedSequences() = received
        override fun saveSettings(value: FriendListeningSettings) {
            if (failSettings) throw IllegalStateException("Test settings failure")
            configuration = value
        }
        override fun saveContacts(value: List<String>) { contactList = value }
        override fun saveSequence(value: Long) { sentSequence = value }
        override fun saveReceivedSequences(value: Map<String, Long>) { received = value }
    }
}
