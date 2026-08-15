package com.eladkay.vibeview.airplay.internal

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Receiver side of AirPlay's legacy PIN pairing.
 *
 * When a receiver advertises password protection, senders do **not** use HTTP Digest
 * authentication — they run SRP-6a against `/pair-setup-pin`, proving knowledge of the
 * PIN shown on screen without ever transmitting it. Three exchanges:
 *
 * 1. `{method: "pin", user: I}` → `{pk: B, salt: s}`
 * 2. `{pk: A, proof: M1}`       → `{proof: M2}`   (wrong PIN fails here)
 * 3. `{epk, authTag}`           → `{epk, authTag}` (long-term key exchange)
 *
 * Apple's variant uses the RFC 5054 2048-bit group with SHA-1, and replaces several
 * standard SRP routines: `u` hashes the unpadded A and B, and the session key is the
 * two-block construction in [sessionKeyHash] rather than a plain hash of S.
 *
 * Derived from the protocol as implemented in
 * [AirPlayAuth](https://github.com/funtax/AirPlayAuth) (MIT, Copyright (c) 2017 Martin),
 * which is the sender side of the same exchange.
 *
 * One instance per pairing attempt; not thread-safe.
 */
internal class SrpPinPairing(private val pin: String) {

    private val random = SecureRandom()

    private var identity: String = ""
    private var salt: ByteArray = ByteArray(0)
    private var verifier: BigInteger = BigInteger.ZERO
    private var privateB: BigInteger = BigInteger.ZERO
    private var publicB: BigInteger = BigInteger.ZERO
    private var sessionKey: ByteArray? = null

    /** Client's long-term public key, recovered in step 3 when it can be decrypted. */
    var clientPublicKey: ByteArray? = null
        private set

    /** Step 1: the receiver's public value and the salt, for the given user identity. */
    fun begin(user: String): Challenge {
        identity = user
        // The sender round-trips the salt through a BigInteger, which would strip a
        // leading zero byte and change the hashes; keep the first byte non-zero.
        salt = ByteArray(SALT_LENGTH).also {
            do {
                random.nextBytes(it)
            } while (it[0] == 0.toByte())
        }

        verifier = GENERATOR.modPow(computeX(salt, identity, pin), PRIME)
        privateB = BigInteger(PRIVATE_VALUE_BITS, random).mod(PRIME)
        val k = hashPaddedPair(PRIME, GENERATOR)
        publicB = (k.multiply(verifier).add(GENERATOR.modPow(privateB, PRIME))).mod(PRIME)

        return Challenge(pk = publicB.toUnsignedBytes(), salt = salt)
    }

    /**
     * Step 2: checks the sender's proof and returns ours.
     *
     * @throws SecurityException if the PIN did not match
     */
    fun verify(clientPublicValue: ByteArray, clientProof: ByteArray): ByteArray {
        check(publicB.signum() != 0) { "verify() called before begin()" }

        val a = BigInteger(1, clientPublicValue)
        if (a.mod(PRIME).signum() == 0) throw SecurityException("Invalid client public value")

        val u = BigInteger(1, sha1(a.toUnsignedBytes() + publicB.toUnsignedBytes()))
        val s = a.multiply(verifier.modPow(u, PRIME)).mod(PRIME).modPow(privateB, PRIME)
        val key = sessionKeyHash(s)
        sessionKey = key

        val expected = clientEvidence(a, key)
        val offered = BigInteger(1, clientProof)
        if (expected != offered) throw SecurityException("PIN mismatch")

        return serverEvidence(a, offered, key).toUnsignedBytes()
    }

    /**
     * Step 3: exchanges long-term keys. The sender's key is decrypted when possible —
     * the PIN has already been proven by this point, so a failure here is logged by the
     * caller rather than treated as an authentication failure.
     *
     * @param ourPublicKey our Ed25519 public key, returned encrypted to the sender
     */
    fun exchangeKeys(epk: ByteArray, authTag: ByteArray, ourPublicKey: ByteArray): KeyExchange {
        val key = sessionKey ?: throw IllegalStateException("exchangeKeys() before verify()")
        val aesKey = SecretKeySpec(sha512("Pair-Setup-AES-Key", key).copyOf(AES_KEY_LENGTH), "AES")
        val iv = sha512("Pair-Setup-AES-IV", key).copyOf(AES_KEY_LENGTH).also { incrementInPlace(it) }

        clientPublicKey = runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(epk + authTag)
            }
        }.getOrNull()

        val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(ourPublicKey)
        }
        val split = sealed.size - GCM_TAG_BITS / 8
        return KeyExchange(epk = sealed.copyOfRange(0, split), authTag = sealed.copyOfRange(split, sealed.size))
    }

    data class Challenge(val pk: ByteArray, val salt: ByteArray) {
        override fun equals(other: Any?) =
            other is Challenge && pk.contentEquals(other.pk) && salt.contentEquals(other.salt)

        override fun hashCode() = 31 * pk.contentHashCode() + salt.contentHashCode()
    }

    data class KeyExchange(val epk: ByteArray, val authTag: ByteArray) {
        override fun equals(other: Any?) =
            other is KeyExchange && epk.contentEquals(other.epk) && authTag.contentEquals(other.authTag)

        override fun hashCode() = 31 * epk.contentHashCode() + authTag.contentHashCode()
    }

    // ---- SRP primitives, matching the sender's routines exactly ----

    /** `M1 = H( (H(N) xor H(g)) | H(I) | s | A | B | K )` */
    private fun clientEvidence(a: BigInteger, key: ByteArray): BigInteger {
        val hN = sha1(PRIME.toUnsignedBytes())
        val hG = sha1(GENERATOR.toUnsignedBytes())
        val hXor = ByteArray(hN.size) { (hN[it].toInt() xor hG[it].toInt()).toByte() }
        val digest = MessageDigest.getInstance(HASH)
        digest.update(hXor)
        digest.update(sha1(identity.toByteArray(Charsets.UTF_8)))
        digest.update(BigInteger(1, salt).toUnsignedBytes())
        digest.update(a.toUnsignedBytes())
        digest.update(publicB.toUnsignedBytes())
        digest.update(key)
        return BigInteger(1, digest.digest())
    }

    /** `M2 = H(A | M1 | K)` */
    private fun serverEvidence(a: BigInteger, m1: BigInteger, key: ByteArray): BigInteger {
        val digest = MessageDigest.getInstance(HASH)
        digest.update(a.toUnsignedBytes())
        digest.update(m1.toUnsignedBytes())
        digest.update(key)
        return BigInteger(1, digest.digest())
    }

    /** `x = H(s | H(I | ":" | P))` */
    private fun computeX(salt: ByteArray, identity: String, password: String): BigInteger {
        val inner = sha1("$identity:$password".toByteArray(Charsets.UTF_8))
        return BigInteger(1, sha1(BigInteger(1, salt).toUnsignedBytes() + inner))
    }

    /**
     * `K = H(S | 00000000) || H(S | 00000001)` — Apple's two-block session key, not the
     * single hash of S that plain SRP-6a uses.
     */
    private fun sessionKeyHash(s: BigInteger): ByteArray {
        val bytes = s.toUnsignedBytes()
        return sha1(bytes + byteArrayOf(0, 0, 0, 0)) + sha1(bytes + byteArrayOf(0, 0, 0, 1))
    }

    /** `k = H(PAD(N) | PAD(g))`, both padded to the byte length of N. */
    private fun hashPaddedPair(n: BigInteger, g: BigInteger): BigInteger {
        val length = (n.bitLength() + 7) / 8
        val digest = MessageDigest.getInstance(HASH)
        digest.update(n.toPaddedBytes(length))
        digest.update(g.toPaddedBytes(length))
        return BigInteger(1, digest.digest())
    }

    private fun sha1(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance(HASH)
        parts.forEach(digest::update)
        return digest.digest()
    }

    private fun sha512(label: String, key: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(label.toByteArray(Charsets.UTF_8))
        digest.update(key)
        return digest.digest()
    }

    companion object {
        private const val HASH = "SHA-1"
        private const val SALT_LENGTH = 16
        private const val PRIVATE_VALUE_BITS = 256
        private const val AES_KEY_LENGTH = 16
        private const val GCM_TAG_BITS = 128

        /** RFC 5054 2048-bit group. */
        val PRIME: BigInteger = BigInteger(
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050" +
                "A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50" +
                "E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B8" +
                "55F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773B" +
                "CA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748" +
                "544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6" +
                "AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB6" +
                "94B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73",
            16,
        )
        val GENERATOR: BigInteger = BigInteger.valueOf(2)

        /** Minimal big-endian form, matching the sender's BigInteger serialisation. */
        fun BigInteger.toUnsignedBytes(): ByteArray {
            val bytes = toByteArray()
            return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }

        private fun BigInteger.toPaddedBytes(length: Int): ByteArray {
            val bytes = toUnsignedBytes()
            if (bytes.size >= length) return bytes
            return ByteArray(length - bytes.size) + bytes
        }

        /** Big-endian increment with carry. */
        private fun incrementInPlace(value: ByteArray) {
            for (i in value.indices.reversed()) {
                value[i] = (value[i] + 1).toByte()
                if (value[i] != 0.toByte()) return
            }
        }
    }
}
