package com.alananasss.kittytune.data.friends

import com.alananasss.kittytune.domain.Track
import com.alananasss.kittytune.domain.User
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Opt-in operational check. Uses only ephemeral identities and synthetic public-track metadata. */
class FriendListeningRelayIntegrationTest {
    @Test
    fun `two independent TLS clients receive anonymous playback name opt in and pause`() {
        assumeTrue(java.lang.Boolean.getBoolean("kittytune.qa.friendRelay"))
        val ownerIdentity = FriendIdentity.generate()
        val owner = FriendListeningEngine(ownerIdentity, EphemeralStore(), FriendPresenceRelay())
        val listener = FriendListeningEngine(FriendIdentity.generate(), EphemeralStore(), FriendPresenceRelay())
        try {
            listener.addFriend(ownerIdentity.invite.code)
            listener.start()
            owner.updatePlayback(
                Track(2320209185L, "KittyTune relay QA", null, 170_000, User(0, "QA", null),
                    permalinkUrl = "https://soundcloud.com/surf7/goose-coat"),
                true, "KittyTune QA",
            )
            owner.setSharingEnabled(true)
            owner.start()
            await("anonymous playback") { listener.activities.value.isNotEmpty() }
            assertEquals("KittyTune relay QA", listener.activities.value.single().track.title)
            assertNull(listener.activities.value.single().displayName)
            owner.setShowName(true)
            await("name opt in") { listener.activities.value.singleOrNull()?.displayName == "KittyTune QA" }
            owner.updatePlayback(null, false, null)
            await("pause clear", timeoutMs = 5_000) { listener.activities.value.isEmpty() }
        } finally {
            owner.setSharingEnabled(false)
            owner.close()
            listener.close()
        }
    }

    private fun await(stage: String, timeoutMs: Long = 45_000, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!predicate()) {
            check(System.nanoTime() < deadline) { "Relay did not deliver $stage within $timeoutMs ms" }
            Thread.sleep(100)
        }
    }

    private class EphemeralStore : FriendStore {
        private var configuration = FriendListeningSettings()
        private var contactList = emptyList<String>()
        private var sentSequence = 0L
        private var received = emptyMap<String, Long>()
        override fun settings() = configuration
        override fun contacts() = contactList
        override fun sequence() = sentSequence
        override fun receivedSequences() = received
        override fun saveSettings(value: FriendListeningSettings) { configuration = value }
        override fun saveContacts(value: List<String>) { contactList = value }
        override fun saveSequence(value: Long) { sentSequence = value }
        override fun saveReceivedSequences(value: Map<String, Long>) { received = value }
    }
}
