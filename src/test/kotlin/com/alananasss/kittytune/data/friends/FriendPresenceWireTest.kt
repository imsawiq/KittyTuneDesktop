package com.alananasss.kittytune.data.friends

import org.junit.Test
import java.security.KeyPair
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FriendPresenceWireTest {
    private val now = 1_800_000_000_000L
    private fun presence(sequence: Long = 1) = FriendPresence(sequence, now, now + 30_000, null,
        FriendPublicTrack(123, "Song", "Artist", "https://soundcloud.com/artist/song", null, "soundcloud", 200_000, "Album"))

    @Test fun `invite pins owner public key and read secret without disclosing a signing key`() {
        val owner = FriendIdentity.generate()
        val invite = assertNotNull(FriendInvite.parse(owner.invite.code))
        val raw = Base64.getUrlDecoder().decode(invite.code.substring(6))
        assertEquals(76, raw.size)
        assertEquals(owner.keys.public.encoded.toList(), raw.drop(32))
        assertEquals(owner.invite.readKey.toList(), raw.take(32))
        assertNull(FriendInvite.parse(invite.code + "="))
        assertNull(FriendInvite.parse("KTFR1.invalid"))
    }

    @Test fun `signed encrypted public presence round trips without an account name by default`() {
        val owner = FriendIdentity.generate()
        val opened = assertNotNull(FriendPresenceWire.open(owner.invite, FriendPresenceWire.seal(owner, presence()), now))
        assertEquals(presence(), opened)
        assertNull(opened.displayName)
    }

    @Test fun `readers cannot impersonate the owner even though they know the encryption secret`() {
        val owner = FriendIdentity.generate()
        val attacker = FriendIdentity.generate()
        val forgery = FriendIdentity(KeyPair(owner.keys.public, attacker.keys.private), owner.invite)
        assertNull(FriendPresenceWire.open(owner.invite, FriendPresenceWire.seal(forgery, presence()), now))
    }

    @Test fun `wrong invitation tampering expired oversized and future frames are rejected`() {
        val owner = FriendIdentity.generate()
        val sealed = FriendPresenceWire.seal(owner, presence())
        assertNull(FriendPresenceWire.open(FriendIdentity.generate().invite, sealed, now))
        assertNull(FriendPresenceWire.open(owner.invite, sealed.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }, now))
        assertNull(FriendPresenceWire.open(owner.invite, sealed, now + 30_000))
        assertNull(FriendPresenceWire.open(owner.invite, sealed, now - 5_001))
        assertNull(FriendPresenceWire.open(owner.invite, ByteArray(8193), now))
        assertNull(FriendPresenceWire.open(owner.invite, FriendPresenceWire.seal(owner, presence(0)), now))
    }

    @Test fun `replay guard rejects duplicate reordered and persisted older messages`() {
        val replay = FriendReplayGuard(mapOf("friend" to 10))
        assertEquals(false, replay.accept("friend", 9))
        assertEquals(false, replay.accept("friend", 10))
        assertEquals(true, replay.accept("friend", 11))
        assertEquals(false, FriendReplayGuard(replay.snapshot()).accept("friend", 11))
    }
}
