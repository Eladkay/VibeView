package com.eladkay.vibeview.dlna.internal

import com.eladkay.vibeview.dlna.DlnaStatus
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * GENA eventing: tracks control-point subscriptions and pushes `NOTIFY` requests when
 * playback state changes, so control points show live state instead of relying on
 * polling.
 *
 * Notifications are delivered on a small background executor — a slow or vanished
 * subscriber must never block the media pipeline.
 */
internal class GenaSubscriptions {

    private class Subscription(
        val sid: String,
        val callbacks: List<String>,
        val serviceType: String,
        @Volatile var expiresAtMillis: Long,
        val eventKey: AtomicLong = AtomicLong(0),
    )

    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val sender = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gena-notify").apply { isDaemon = true }
    }

    /**
     * Registers a new subscription.
     *
     * @param callbackHeader raw CALLBACK header, e.g. `<http://host:port/cb>`
     * @return the assigned SID, or null when the header carries no usable URL
     */
    fun subscribe(callbackHeader: String?, serviceType: String, timeoutSeconds: Long, nowMillis: Long): String? {
        val callbacks = parseCallbacks(callbackHeader)
        if (callbacks.isEmpty()) return null
        val sid = "uuid:${UUID.randomUUID()}"
        subscriptions[sid] = Subscription(
            sid = sid,
            callbacks = callbacks,
            serviceType = serviceType,
            expiresAtMillis = nowMillis + timeoutSeconds * 1000,
        )
        return sid
    }

    /** Extends an existing subscription; returns false if the SID is unknown. */
    fun renew(sid: String?, timeoutSeconds: Long, nowMillis: Long): Boolean {
        val subscription = subscriptions[sid ?: return false] ?: return false
        subscription.expiresAtMillis = nowMillis + timeoutSeconds * 1000
        return true
    }

    fun unsubscribe(sid: String?) {
        subscriptions.remove(sid ?: return)
    }

    fun clear() {
        subscriptions.clear()
        sender.shutdownNow()
    }

    /** Pushes the current transport state to every live AVTransport subscriber. */
    fun notifyTransportState(status: DlnaStatus, nowMillis: Long) {
        val body = lastChangeXml(status)
        for (subscription in subscriptions.values) {
            if (subscription.serviceType != UpnpDevice.SERVICE_AVT) continue
            if (subscription.expiresAtMillis < nowMillis) {
                subscriptions.remove(subscription.sid)
                continue
            }
            val key = subscription.eventKey.getAndIncrement()
            for (callback in subscription.callbacks) {
                runCatching { sender.execute { post(callback, subscription.sid, key, body) } }
            }
        }
    }

    private fun post(callback: String, sid: String, eventKey: Long, body: String) {
        runCatching {
            val connection = (URL(callback).openConnection() as HttpURLConnection).apply {
                requestMethod = "NOTIFY"
                doOutput = true
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("CONTENT-TYPE", "text/xml; charset=\"utf-8\"")
                setRequestProperty("NT", "upnp:event")
                setRequestProperty("NTS", "upnp:propchange")
                setRequestProperty("SID", sid)
                setRequestProperty("SEQ", eventKey.toString())
            }
            connection.outputStream.use { out: OutputStream ->
                out.write(body.toByteArray(Charsets.UTF_8))
            }
            connection.responseCode
            connection.disconnect()
        }.onFailure { log.debug("GENA notify to {} failed: {}", callback, it.toString()) }
    }

    /** The AVTransport LastChange document, XML-escaped inside a property set. */
    private fun lastChangeXml(status: DlnaStatus): String {
        val inner = buildString {
            append("""<Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/">""")
            append("""<InstanceID val="0">""")
            append("""<TransportState val="""").append(status.state.upnpName).append(""""/>""")
            append("""<CurrentTrackDuration val="""").append(UpnpTime.format(status.durationSeconds)).append(""""/>""")
            append("""<RelativeTimePosition val="""").append(UpnpTime.format(status.positionSeconds)).append(""""/>""")
            append("""<CurrentTrackURI val="""").append(UpnpTime.xmlEscape(status.uri.orEmpty())).append(""""/>""")
            append("</InstanceID></Event>")
        }
        return """<?xml version="1.0" encoding="utf-8"?>""" +
            """<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0"><e:property>""" +
            "<LastChange>" + UpnpTime.xmlEscape(inner) + "</LastChange>" +
            "</e:property></e:propertyset>"
    }

    /** CALLBACK headers list one or more `<url>` values. */
    private fun parseCallbacks(header: String?): List<String> {
        if (header.isNullOrBlank()) return emptyList()
        return Regex("<([^>]+)>").findAll(header)
            .map { it.groupValues[1].trim() }
            .filter { it.startsWith("http://", ignoreCase = true) }
            .toList()
    }

    companion object {
        private val log = LoggerFactory.getLogger(GenaSubscriptions::class.java)
        const val DEFAULT_TIMEOUT_SECONDS = 1800L
    }
}
