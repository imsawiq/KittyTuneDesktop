package com.alananasss.kittytune.data.friends

import com.alananasss.kittytune.core.NamedPrefs
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal data class FriendInvite(val code: String, val publicKey: java.security.PublicKey, val readKey: ByteArray) {
    val topic: String get() = "kittytune/friends/v1/" + MessageDigest.getInstance("SHA-256")
        .digest(code.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }

    companion object {
        fun parse(raw: String): FriendInvite? = runCatching {
            val code = raw.trim()
            require(code.length in 90..180 && code.startsWith("KTFR1."))
            val bytes = Base64.getUrlDecoder().decode(code.substring(6))
            require(bytes.size == 76)
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(bytes.copyOfRange(32, 76)))
            val canonical = "KTFR1." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            require(code == canonical)
            FriendInvite(canonical, key, bytes.copyOfRange(0, 32))
        }.getOrNull()
    }
}

internal class FriendIdentity(val keys: KeyPair, val invite: FriendInvite) {
    companion object {
        fun generate(): FriendIdentity = of(KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
            ByteArray(32).also(SecureRandom()::nextBytes))

        private fun of(keys: KeyPair, readKey: ByteArray): FriendIdentity {
            val encoded = readKey + keys.public.encoded
            val code = "KTFR1." + Base64.getUrlEncoder().withoutPadding().encodeToString(encoded)
            return FriendIdentity(keys, requireNotNull(FriendInvite.parse(code)))
        }

        fun load(prefs: NamedPrefs): FriendIdentity {
            val existing = runCatching {
                val invite = requireNotNull(FriendInvite.parse(prefs.getString("own_invite", "").orEmpty()))
                val privateBytes = Base64.getDecoder().decode(prefs.getString("private_key", ""))
                val privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(privateBytes))
                val identity = FriendIdentity(KeyPair(invite.publicKey, privateKey), invite)
                val proof = java.security.Signature.getInstance("Ed25519").apply { initSign(privateKey); update(byteArrayOf(1)) }.sign()
                require(java.security.Signature.getInstance("Ed25519").run { initVerify(invite.publicKey); update(byteArrayOf(1)); verify(proof) })
                identity
            }.getOrNull()
            if (existing != null) return existing
            return generate().also {
                prefs.putString("private_key", Base64.getEncoder().encodeToString(it.keys.private.encoded))
                prefs.putString("own_invite", it.invite.code)
            }
        }
    }
}
