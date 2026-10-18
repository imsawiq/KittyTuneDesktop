package com.alananasss.kittytune.data.friends

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class FriendPresence(
    val sequence: Long, val sentAtMs: Long, val expiresAtMs: Long,
    val displayName: String?, val track: FriendPublicTrack?,
)

internal object FriendPresenceWire {
    const val MAX_BYTES = 8192
    const val TTL_MS = 30_000L
    private val random = SecureRandom()

    fun seal(identity: FriendIdentity, presence: FriendPresence): ByteArray {
        val canonical = encode(presence)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(identity.keys.private); update(canonical); sign()
        }
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, identity.invite, iv)
        return (iv + cipher.doFinal(canonical + signature)).also { require(it.size <= MAX_BYTES) }
    }

    fun open(invite: FriendInvite, bytes: ByteArray, now: Long): FriendPresence? = runCatching {
        require(bytes.size in 93..MAX_BYTES)
        val plain = cipher(Cipher.DECRYPT_MODE, invite, bytes.copyOfRange(0, 12)).doFinal(bytes.copyOfRange(12, bytes.size))
        require(plain.size > 64)
        val canonical = plain.copyOfRange(0, plain.size - 64)
        require(Signature.getInstance("Ed25519").run {
            initVerify(invite.publicKey); update(canonical); verify(plain.copyOfRange(plain.size - 64, plain.size))
        })
        decode(canonical).also {
            require(it.sequence > 0 && it.sentAtMs > 0 && it.expiresAtMs - it.sentAtMs == TTL_MS)
            require(it.sentAtMs <= now + 5_000 && it.expiresAtMs > now)
        }
    }.getOrNull()

    private fun cipher(mode: Int, invite: FriendInvite, iv: ByteArray): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(invite.readKey, "AES"), GCMParameterSpec(128, iv))
        updateAAD(invite.topic.toByteArray(Charsets.US_ASCII))
    }

    private fun encode(presence: FriendPresence): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(1)
            output.writeLong(presence.sequence)
            output.writeLong(presence.sentAtMs)
            output.writeLong(presence.expiresAtMs)
            output.writeUTF(presence.displayName.orEmpty())
            output.writeBoolean(presence.track != null)
            presence.track?.let { track ->
                output.writeLong(track.id)
                output.writeUTF(track.title); output.writeUTF(track.artist); output.writeUTF(track.url)
                output.writeUTF(track.artwork.orEmpty()); output.writeUTF(track.source); output.writeLong(track.durationMs)
                output.writeUTF(track.albumTitle.orEmpty())
            }
        }
    }.toByteArray().also { require(it.size <= MAX_BYTES - 92) }

    private fun decode(canonical: ByteArray): FriendPresence = DataInputStream(ByteArrayInputStream(canonical)).use { input ->
        require(input.readInt() == 1)
        val sequence = input.readLong()
        val sentAt = input.readLong()
        val expiresAt = input.readLong()
        val name = input.readUTF().takeIf { it.isNotEmpty() }
        require(name == null || name == FriendPublicTrack.text(name, 60))
        val track = if (input.readBoolean()) {
            val value = FriendPublicTrack(input.readLong(), input.readUTF(), input.readUTF(), input.readUTF(),
                input.readUTF().takeIf { it.isNotEmpty() }, input.readUTF(), input.readLong(), input.readUTF().takeIf { it.isNotEmpty() })
            require(FriendPublicTrack.of(value.toTrack()) == value)
            value
        } else null
        require(input.available() == 0)
        FriendPresence(sequence, sentAt, expiresAt, name, track)
    }
}

internal class FriendReplayGuard(initial: Map<String, Long> = emptyMap()) {
    private val highest = initial.toMutableMap()
    fun accept(code: String, sequence: Long): Boolean {
        if (sequence <= (highest[code] ?: 0)) return false
        highest[code] = sequence
        return true
    }
    fun remove(code: String) { highest.remove(code) }
    fun snapshot(): Map<String, Long> = highest.toMap()
}
