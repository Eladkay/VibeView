package com.eladkay.vibeview.ui

import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import com.eladkay.vibeview.Prefs
import com.eladkay.vibeview.R
import com.eladkay.vibeview.service.AirPlayService

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<EditTextPreference>(Prefs.KEY_DEVICE_NAME)?.apply {
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                if (text.isNullOrBlank()) {
                    text = getString(R.string.app_name)
                }
            }
        }

        override fun onResume() {
            super.onResume()
            preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            val ctx = context ?: return
            when (key) {
                Prefs.KEY_REQUIRE_PASSCODE -> {
                    if (Prefs.requirePasscode(ctx)) {
                        // Generate and reveal a code so the field isn't blank.
                        findPreference<EditTextPreference>(Prefs.KEY_PASSCODE)?.text = Prefs.passcode(ctx)
                    }
                    AirPlayService.restart(ctx)
                }
                // The Bonjour name, audio pipeline, and passcode are all wired at server start.
                Prefs.KEY_DEVICE_NAME, Prefs.KEY_AUDIO_ENABLED, Prefs.KEY_PASSCODE ->
                    AirPlayService.restart(ctx)
            }
        }
    }
}
