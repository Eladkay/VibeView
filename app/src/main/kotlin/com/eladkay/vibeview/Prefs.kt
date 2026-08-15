package com.eladkay.vibeview

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.preference.PreferenceManager
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

object Prefs {
    const val KEY_DEVICE_NAME = "device_name"
    const val KEY_AUDIO_ENABLED = "audio_enabled"
    const val KEY_AUTOSTART = "autostart"
    const val KEY_REQUIRE_PASSCODE = "require_passcode"
    const val KEY_PASSCODE = "passcode"
    const val KEY_DLNA_UUID = "dlna_uuid"

    fun get(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    fun deviceName(context: Context): String =
        get(context).getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.app_name)

    fun audioEnabled(context: Context): Boolean =
        get(context).getBoolean(KEY_AUDIO_ENABLED, true)

    fun autostart(context: Context): Boolean =
        get(context).getBoolean(KEY_AUTOSTART, true)

    fun requirePasscode(context: Context): Boolean =
        get(context).getBoolean(KEY_REQUIRE_PASSCODE, false)

    /**
     * The passcode senders must enter when [requirePasscode] is on. A 4-digit code
     * is generated and persisted the first time it's needed so it stays stable.
     */
    fun passcode(context: Context): String {
        val prefs = get(context)
        var code = prefs.getString(KEY_PASSCODE, null)
        if (code.isNullOrBlank() || code.length != 4 || code.any { !it.isDigit() }) {
            code = String.format(Locale.US, "%04d", Random.nextInt(10000))
            prefs.edit().putString(KEY_PASSCODE, code).apply()
        }
        return code
    }

    /** Stable UPnP device UUID advertised by the DLNA renderer; generated once. */
    fun dlnaUuid(context: Context): String {
        val prefs = get(context)
        var uuid = prefs.getString(KEY_DLNA_UUID, null)
        if (uuid.isNullOrBlank()) {
            uuid = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DLNA_UUID, uuid).apply()
        }
        return uuid
    }

    /**
     * Stable pseudo-MAC advertised as the AirPlay device id, derived from ANDROID_ID
     * with the locally-administered bit set so it can't collide with a real NIC.
     */
    fun deviceId(context: Context): String {
        val seed = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "vibeview"
        val hash = seed.hashCode()
        val bytes = byteArrayOf(
            0x02,
            (hash ushr 24).toByte(),
            (hash ushr 16).toByte(),
            (hash ushr 8).toByte(),
            hash.toByte(),
            seed.length.toByte(),
        )
        return bytes.joinToString(":") { String.format(Locale.US, "%02X", it) }
    }
}
