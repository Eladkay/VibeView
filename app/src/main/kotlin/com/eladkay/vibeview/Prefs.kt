package com.eladkay.vibeview

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.preference.PreferenceManager
import java.util.Locale

object Prefs {
    const val KEY_DEVICE_NAME = "device_name"
    const val KEY_AUDIO_ENABLED = "audio_enabled"
    const val KEY_AUTOSTART = "autostart"

    fun get(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    fun deviceName(context: Context): String =
        get(context).getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.app_name)

    fun audioEnabled(context: Context): Boolean =
        get(context).getBoolean(KEY_AUDIO_ENABLED, true)

    fun autostart(context: Context): Boolean =
        get(context).getBoolean(KEY_AUTOSTART, true)

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
