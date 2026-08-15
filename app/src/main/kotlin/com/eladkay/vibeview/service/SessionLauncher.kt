package com.eladkay.vibeview.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.eladkay.vibeview.R
import com.eladkay.vibeview.ui.MainActivity

/**
 * Brings the receiver UI to the foreground when a session starts.
 *
 * This matters because only [MainActivity] owns the rendering surface: without it
 * on screen, an incoming mirror/cast session decodes into nothing. Since the
 * receiver is meant to sit in the background (and start at boot), something has to
 * pull the UI forward when a device connects.
 *
 * Android restricts background activity starts, and the rules differ by version and
 * by OEM. Two mechanisms are used together:
 *  1. A direct `startActivity`, which succeeds on most TV builds.
 *  2. A high-priority notification carrying a full-screen intent, which the system
 *     surfaces when the direct start is refused.
 */
internal object SessionLauncher {

    private const val TAG = "SessionLauncher"
    private const val LAUNCH_NOTIFICATION_ID = 2

    fun bringToForeground(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        val started = runCatching { context.startActivity(intent) }
            .onFailure { Log.i(TAG, "Direct activity start refused; using full-screen intent", it) }
            .isSuccess

        if (!started) postFullScreenIntent(context, intent)
    }

    private fun postFullScreenIntent(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val pending = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, AirPlayService.CHANNEL_ID_SESSION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_session_started))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(LAUNCH_NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "Could not post full-screen intent", it) }
    }

    /** Clears the launch notification once the UI is up or the session ended. */
    fun clear(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(LAUNCH_NOTIFICATION_ID)
        }
    }
}
