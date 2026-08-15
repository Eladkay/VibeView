package com.eladkay.vibeview.airplay

import com.eladkay.vibeview.airplay.internal.SrpPinPairing
import com.eladkay.vibeview.airplay.internal.SrpPinPairing.Companion.toUnsignedBytes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Drives the receiver's SRP implementation with a sender written independently from the
 * protocol description, so the two only agree if the maths is right. Without a real
 * Apple device to test against, this round trip is the evidence that pairing works.
 */
class SrpPinPairingTest {

    private val n = SrpPinPairing.PRIME
    private val g = SrpPinPairing.GENERATOR

    private fun sha1(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        parts.forEach(digest::update)
        return digest.digest()
    }

    private fun pad(value: BigInteger, length: Int): ByteArray {
        val bytes = value.toUnsignedBytes()
        return if (bytes.size >= length) bytes else ByteArray(length - bytes.size) + bytes
    }

    /** The sender half of the exchange. */
    private inner class Sender(val identity: String, val pin: String) {
        private val a = BigInteger(256, SecureRandom())
        val publicA: BigInteger = g.modPow(a, n)
        lateinit var sessionKey: ByteArray
            private set

        fun proof(salt: ByteArray, publicB: BigInteger): BigInteger {
            val length = (n.bitLength() + 7) / 8
            val k = BigInteger(1, sha1(pad(n, length), pad(g, length)))
            val x = BigInteger(
                1,
                sha1(BigInteger(1, salt).toUnsignedBytes(), sha1("$identity:$pin".toByteArray()))
            )
            val u = BigInteger(1, sha1(publicA.toUnsignedBytes(), publicB.toUnsignedBytes()))
            // S = (B - k*g^x) ^ (a + u*x)
            val base = publicB.subtract(k.multiply(g.modPow(x, n)).mod(n)).mod(n)
            val s = base.modPow(a.add(u.multiply(x)), n)

            sessionKey = sha1(s.toUnsignedBytes(), byteArrayOf(0, 0, 0, 0)) +
                sha1(s.toUnsignedBytes(), byteArrayOf(0, 0, 0, 1))

            val hN = sha1(n.toUnsignedBytes())
            val hG = sha1(g.toUnsignedBytes())
            val hXor = ByteArray(hN.size) { (hN[it].toInt() xor hG[it].toInt()).toByte() }
            return BigInteger(
                1,
                sha1(
                    hXor,
                    sha1(identity.toByteArray()),
                    BigInteger(1, salt).toUnsignedBytes(),
                    publicA.toUnsignedBytes(),
                    publicB.toUnsignedBytes(),
                    sessionKey,
                )
            )
        }

        fun expectedServerProof(m1: BigInteger): BigInteger = BigInteger(
            1,
            sha1(publicA.toUnsignedBytes(), m1.toUnsignedBytes(), sessionKey)
        )
    }

    @Test
    fun `the 2048-bit group is a safe prime with generator 2`() {
        assertEquals(2048, n.bitLength())
        assertTrue(n.isProbablePrime(64), "N must be prime")
        // RFC 5054 groups are safe primes: (N-1)/2 is also prime.
        val q = n.subtract(BigInteger.ONE).divide(BigInteger.TWO)
        assertTrue(q.isProbablePrime(64), "(N-1)/2 must be prime")
        assertEquals(BigInteger.TWO, g)
    }

    @Test
    fun `a sender with the right PIN completes the exchange`() {
        val receiver = SrpPinPairing("3456")
        val sender = Sender("A1B2C3D4E5F60718", "3456")

        val challenge = receiver.begin(sender.identity)
        val publicB = BigInteger(1, challenge.pk)
        val m1 = sender.proof(challenge.salt, publicB)

        val m2 = receiver.verify(sender.publicA.toUnsignedBytes(), m1.toUnsignedBytes())

        // The receiver's proof must be the one the sender independently expects: this is
        // what tells the sender it reached a receiver that really knew the PIN.
        assertEquals(sender.expectedServerProof(m1), BigInteger(1, m2))
    }

    @Test
    fun `a sender with the wrong PIN is rejected`() {
        val receiver = SrpPinPairing("3456")
        val sender = Sender("A1B2C3D4E5F60718", "9999")

        val challenge = receiver.begin(sender.identity)
        val m1 = sender.proof(challenge.salt, BigInteger(1, challenge.pk))

        assertThrows<SecurityException> {
            receiver.verify(sender.publicA.toUnsignedBytes(), m1.toUnsignedBytes())
        }
    }

    @Test
    fun `a sender using a different identity is rejected`() {
        val receiver = SrpPinPairing("3456")
        val challenge = receiver.begin("A1B2C3D4E5F60718")
        // Same PIN, but the proof is bound to a different user identity.
        val impostor = Sender("FFFFFFFFFFFFFFFF", "3456")
        val m1 = impostor.proof(challenge.salt, BigInteger(1, challenge.pk))

        assertThrows<SecurityException> {
            receiver.verify(impostor.publicA.toUnsignedBytes(), m1.toUnsignedBytes())
        }
    }

    @Test
    fun `rejects a degenerate client public value`() {
        val receiver = SrpPinPairing("3456")
        receiver.begin("A1B2C3D4E5F60718")
        // A ≡ 0 mod N makes the shared secret zero regardless of the PIN.
        assertThrows<SecurityException> {
            receiver.verify(n.toUnsignedBytes(), ByteArray(20))
        }
    }

    @Test
    fun `key exchange round-trips the long-term keys`() {
        val receiver = SrpPinPairing("3456")
        val sender = Sender("A1B2C3D4E5F60718", "3456")
        val challenge = receiver.begin(sender.identity)
        val m1 = sender.proof(challenge.salt, BigInteger(1, challenge.pk))
        receiver.verify(sender.publicA.toUnsignedBytes(), m1.toUnsignedBytes())

        // Encrypt a sender key with the agreed session key, exactly as a sender would.
        val senderKey = ByteArray(32) { it.toByte() }
        val sealed = sealWithSessionKey(sender.sessionKey, senderKey)
        val ourKey = ByteArray(32) { (it + 100).toByte() }

        val response = receiver.exchangeKeys(sealed.first, sealed.second, ourKey)

        assertNotNull(receiver.clientPublicKey, "sender's long-term key should decrypt")
        assertArrayEquals(senderKey, receiver.clientPublicKey)
        // Our key comes back encrypted under the same session key.
        assertArrayEquals(ourKey, openWithSessionKey(sender.sessionKey, response.epk, response.authTag))
    }

    private fun aesParams(sessionKey: ByteArray): Pair<javax.crypto.spec.SecretKeySpec, ByteArray> {
        fun sha512(label: String): ByteArray = MessageDigest.getInstance("SHA-512").run {
            update(label.toByteArray()); update(sessionKey); digest()
        }
        val key = javax.crypto.spec.SecretKeySpec(sha512("Pair-Setup-AES-Key").copyOf(16), "AES")
        val iv = sha512("Pair-Setup-AES-IV").copyOf(16)
        for (i in iv.indices.reversed()) {
            iv[i] = (iv[i] + 1).toByte()
            if (iv[i] != 0.toByte()) break
        }
        return key to iv
    }

    private fun sealWithSessionKey(sessionKey: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray> {
        val (key, iv) = aesParams(sessionKey)
        val sealed = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run {
            init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
            doFinal(plain)
        }
        return sealed.copyOfRange(0, sealed.size - 16) to sealed.copyOfRange(sealed.size - 16, sealed.size)
    }

    private fun openWithSessionKey(sessionKey: ByteArray, epk: ByteArray, authTag: ByteArray): ByteArray {
        val (key, iv) = aesParams(sessionKey)
        return javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run {
            init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
            doFinal(epk + authTag)
        }
    }
}
