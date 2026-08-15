package com.eladkay.vibeview.airplay

import com.eladkay.vibeview.airplay.internal.DigestAuth
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class DigestAuthTest {

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.ISO_8859_1))
            .joinToString("") { "%02x".format(it) }

    /** Builds a client Authorization header the way a sender would, given our challenge. */
    private fun clientHeader(username: String, realm: String, nonce: String, uri: String, method: String, password: String): String {
        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("$method:$uri")
        val response = md5("$ha1:$nonce:$ha2")
        return "Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\""
    }

    private fun nonceOf(challenge: String): String =
        Regex("nonce=\"([^\"]+)\"").find(challenge)!!.groupValues[1]

    @Test
    fun `disabled auth authorizes everything`() {
        val auth = DigestAuth("airplay", null)
        assertTrue(auth.isAuthorized("OPTIONS", null))
        val blank = DigestAuth("airplay", "")
        assertTrue(blank.isAuthorized("SETUP", null))
    }

    @Test
    fun `rejects when no authorization header`() {
        val auth = DigestAuth("airplay", "1234")
        assertFalse(auth.isAuthorized("OPTIONS", null))
    }

    @Test
    fun `accepts a correct digest response`() {
        val auth = DigestAuth("airplay", "1234")
        val nonce = nonceOf(auth.challenge())
        val header = clientHeader("AirPlay", "airplay", nonce, "rtsp://x/stream", "SETUP", "1234")
        assertTrue(auth.isAuthorized("SETUP", header))
    }

    @Test
    fun `rejects a wrong password`() {
        val auth = DigestAuth("airplay", "1234")
        val nonce = nonceOf(auth.challenge())
        val header = clientHeader("AirPlay", "airplay", nonce, "rtsp://x/stream", "SETUP", "9999")
        assertFalse(auth.isAuthorized("SETUP", header))
    }

    @Test
    fun `rejects a stale nonce`() {
        val auth = DigestAuth("airplay", "1234")
        val firstNonce = nonceOf(auth.challenge())
        val header = clientHeader("AirPlay", "airplay", firstNonce, "rtsp://x/stream", "SETUP", "1234")
        // A new challenge rotates the nonce, invalidating the old response.
        auth.challenge()
        assertFalse(auth.isAuthorized("SETUP", header))
    }

    @Test
    fun `rejects response bound to a different method`() {
        val auth = DigestAuth("airplay", "1234")
        val nonce = nonceOf(auth.challenge())
        val header = clientHeader("AirPlay", "airplay", nonce, "rtsp://x/stream", "OPTIONS", "1234")
        assertFalse(auth.isAuthorized("SETUP", header))
    }
}
