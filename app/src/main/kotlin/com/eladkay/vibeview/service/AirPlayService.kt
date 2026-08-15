package com.eladkay.vibeview.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.eladkay.vibeview.Prefs
import com.eladkay.vibeview.R
import com.eladkay.vibeview.airplay.AirPlayConfig
import com.eladkay.vibeview.airplay.AirPlayServer
import com.eladkay.vibeview.dlna.DlnaConfig
import com.eladkay.vibeview.dlna.DlnaRenderer
import com.eladkay.vibeview.ui.MainActivity
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * Foreground service owning the AirPlay receiver sockets and Bonjour advertisement.
 * Media rendering is coordinated through [ReceiverSessionHub].
 */
class AirPlayService : Service() {

    private var server: AirPlayServer? = null
    private var dlnaRenderer: DlnaRenderer? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        acquireMulticastLock()
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val stopping = server
        val stoppingDlna = dlnaRenderer
        server = null
        dlnaRenderer = null
        thread(name = "AirPlayStop") {
            runCatching { stopping?.stop() }
            runCatching { stoppingDlna?.stop() }
        }
        ReceiverSessionHub.shutdown()
        ReceiverSessionHub.updateServerInfo(
            ServerInfo(Prefs.deviceName(this), null, running = false)
        )
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        super.onDestroy()
    }

    private fun startServer() {
        val deviceName = Prefs.deviceName(this)
        val passcode = if (Prefs.requirePasscode(this)) Prefs.passcode(this) else null
        val config = AirPlayConfig(
            serverName = deviceName,
            deviceId = Prefs.deviceId(this),
            password = passcode,
        )
        ReceiverSessionHub.audioEnabled = Prefs.audioEnabled(this)

        thread(name = "AirPlayStart") {
            try {
                val address = pickLocalAddress()
                val newServer = AirPlayServer(config, ReceiverSessionHub)
                ReceiverSessionHub.attach(this, newServer)
                newServer.start(address)
                server = newServer

                // DLNA renderer needs a bound address; a failure here (e.g. port 1900
                // busy) must not take down AirPlay.
                if (address != null) {
                    runCatching {
                        val renderer = DlnaRenderer(
                            DlnaConfig(friendlyName = deviceName, uuid = Prefs.dlnaUuid(this)),
                            ReceiverSessionHub,
                        )
                        renderer.start(address)
                        dlnaRenderer = renderer
                    }.onFailure { Log.w(TAG, "DLNA renderer failed to start", it) }
                }

                ReceiverSessionHub.updateServerInfo(
                    ServerInfo(deviceName, address?.hostAddress, running = true, passcode = passcode)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AirPlay server", e)
                ReceiverSessionHub.updateServerInfo(
                    ServerInfo(deviceName, null, running = false, passcode = passcode)
                )
                stopSelf()
            }
        }
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text, Prefs.deviceName(this)))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
        )
    }

    private fun acquireMulticastLock() {
        runCatching {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("vibeview-airplay").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "Could not acquire multicast lock", it) }
    }

    private fun pickLocalAddress(): InetAddress? =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
            .sortedBy { nic ->
                val name = nic.name
                when {
                    name.startsWith("eth") -> 0
                    name.startsWith("wlan") -> 1
                    else -> 2
                }
            }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL_ID = "receiver"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AirPlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AirPlayService::class.java))
        }

        fun restart(context: Context) {
            stop(context)
            start(context)
        }
    }
}
