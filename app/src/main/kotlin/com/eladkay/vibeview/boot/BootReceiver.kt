package com.eladkay.vibeview.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.eladkay.vibeview.Prefs
import com.eladkay.vibeview.service.AirPlayService

/** Starts the receiver on boot so the TV is always AirPlay-discoverable. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.autostart(context)) return
        // Newer Android versions restrict some FGS launches from boot; losing
        // autostart there shouldn't crash the receiver.
        runCatching { AirPlayService.start(context) }
            .onFailure { Log.w("BootReceiver", "Autostart failed", it) }
    }
}
