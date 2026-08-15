package com.eladkay.vibeview.airplay.internal

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * HTTP/RTSP Digest authentication (RFC 2617, the qop-less form AirPlay/RAOP uses)
 * gating a connection behind a passcode. One instance per connection: it remembers
 * the nonce it last issued so it can validate the client's response against it.
 *
 * When [password] is null or blank, authentication is disabled and every request
 * is authorized.
 */
internal class DigestAuth(private val realm: String, private val password: String?) {

    private val enabled = !password.isNullOrEmpty()
    private val secureRandom = SecureRandom()

    @Volatile private var nonce: String? = null

    /** True if authentication is off, or the Authorization header carries a valid response. */
    fun isAuthorized(method: String, authorizationHeader: String?): Boolean {
        if (!enabled) return true
        val header = authorizationHeader?.trim() ?: return false
        if (!header.regionMatches(0, "Digest", 0, 6, ignoreCase = true)) return false
        val currentNonce = nonce ?: return false

        val params = parseParams(header.substring(6))
        if (params["nonce"] != currentNonce) return false
        val username = params["username"] ?: return false
        val headerRealm = params["realm"] ?: return false
        val uri = params["uri"] ?: return false
        val response = params["response"] ?: return false

        val ha1 = md5Hex("$username:$headerRealm:$password")
        val ha2 = md5Hex("$method:$uri")
        val expected = md5Hex("$ha1:$currentNonce:$ha2")
        return constantTimeEquals(expected, response.lowercase())
    }

    /** Produces a fresh challenge value for a `WWW-Authenticate` header and records its nonce. */
    fun challenge(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        val newNonce = bytes.toHex()
        nonce = newNonce
        return "Digest realm=\"$realm\", nonce=\"$newNonce\""
    }

    private fun parseParams(input: String): Map<String, String> {
        val result = HashMap<String, String>()
        // Split on commas that are not inside quotes.
        var i = 0
        val len = input.length
        val sb = StringBuilder()
        val fields = ArrayList<String>()
        var inQuotes = false
        while (i < len) {
            val c = input[i]
            when {
                c == '"' -> { inQuotes = !inQuotes; sb.append(c) }
                c == ',' && !inQuotes -> { fields.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) fields.add(sb.toString())

        for (field in fields) {
            val eq = field.indexOf('=')
            if (eq <= 0) continue
            val key = field.substring(0, eq).trim().lowercase()
            var value = field.substring(eq + 1).trim()
            if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
                value = value.substring(1, value.length - 1)
            }
            result[key] = value
        }
        return result
    }

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.ISO_8859_1)).toHex()

    private fun ByteArray.toHex(): String {
        val hex = CharArray(size * 2)
        for (idx in indices) {
            val v = this[idx].toInt() and 0xFF
            hex[idx * 2] = HEX[v ushr 4]
            hex[idx * 2 + 1] = HEX[v and 0x0F]
        }
        return String(hex)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()
        const val REALM = "airplay"
    }
}
