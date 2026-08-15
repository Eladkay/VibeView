package com.eladkay.vibeview.dlna.internal

import com.eladkay.vibeview.dlna.DlnaConfig
import org.slf4j.LoggerFactory
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * SSDP responder for the renderer: answers M-SEARCH discovery requests and periodically
 * multicasts `ssdp:alive` notifications, sending `ssdp:byebye` on stop.
 */
internal class SsdpResponder(private val config: DlnaConfig, private val address: InetAddress) {

    private val location = "http://${address.hostAddress}:${config.httpPort}${UpnpDevice.DESCRIPTION_PATH}"
    private val uuidUrn = "uuid:${config.uuid}"

    // (notification type, unique service name) pairs this device advertises.
    private val targets: List<Pair<String, String>> = listOf(
        "upnp:rootdevice" to "$uuidUrn::upnp:rootdevice",
        uuidUrn to uuidUrn,
        "$DEVICE_TYPE" to "$uuidUrn::$DEVICE_TYPE",
        "$SERVICE_AVT" to "$uuidUrn::$SERVICE_AVT",
        "$SERVICE_RC" to "$uuidUrn::$SERVICE_RC",
        "$SERVICE_CM" to "$uuidUrn::$SERVICE_CM",
    )

    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var running = false
    private var receiveThread: Thread? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ssdp-announce").apply { isDaemon = true }
    }

    fun start() {
        val group = InetAddress.getByName(MULTICAST_ADDRESS)
        val netIf = runCatching { NetworkInterface.getByInetAddress(address) }.getOrNull()
        val sock = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(SSDP_PORT))
            timeToLive = 4
            runCatching { if (netIf != null) networkInterface = netIf }
        }
        runCatching {
            if (netIf != null) sock.joinGroup(InetSocketAddress(group, SSDP_PORT), netIf)
            else @Suppress("DEPRECATION") sock.joinGroup(group)
        }.onFailure { log.warn("SSDP joinGroup failed: {}", it.toString()) }

        socket = sock
        running = true

        receiveThread = thread(name = "ssdp-receive") { receiveLoop(sock, group) }
        // Announce now and re-announce well within the 1800s cache lifetime.
        scheduler.scheduleWithFixedDelay({ runCatching { announce("ssdp:alive") } }, 0, 900, TimeUnit.SECONDS)
        log.info("SSDP responder started on {}", address.hostAddress)
    }

    fun stop() {
        running = false
        runCatching { announce("ssdp:byebye") }
        scheduler.shutdownNow()
        socket?.close()
        socket = null
        receiveThread?.interrupt()
    }

    private fun receiveLoop(sock: MulticastSocket, group: InetAddress) {
        val buffer = ByteArray(2048)
        while (running) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                sock.receive(packet)
                val message = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
                handleMessage(message, packet.socketAddress)
            } catch (e: Exception) {
                if (running) log.debug("SSDP receive error: {}", e.toString())
            }
        }
        runCatching { if (NetworkInterface.getByInetAddress(address) != null) sock.leaveGroup(group) }
    }

    private fun handleMessage(message: String, sender: SocketAddress) {
        val lines = message.split("\r\n")
        if (lines.isEmpty() || !lines[0].startsWith("M-SEARCH", ignoreCase = true)) return
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) headers[lines[i].substring(0, idx).trim().uppercase()] = lines[i].substring(idx + 1).trim()
        }
        if (headers["MAN"]?.contains("ssdp:discover") != true) return
        val st = headers["ST"]?.trim() ?: return
        respond(st, sender)
    }

    private fun respond(searchTarget: String, sender: SocketAddress) {
        val matches = when (searchTarget) {
            "ssdp:all" -> targets
            else -> targets.filter { it.first == searchTarget }
        }
        val sock = socket ?: return
        for ((nt, usn) in matches) {
            val response = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("CACHE-CONTROL: max-age=1800\r\n")
                append("EXT:\r\n")
                append("LOCATION: ").append(location).append("\r\n")
                append("SERVER: ").append(SERVER).append("\r\n")
                append("ST: ").append(nt).append("\r\n")
                append("USN: ").append(usn).append("\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII)
            runCatching { sock.send(DatagramPacket(response, response.size, sender)) }
        }
    }

    private fun announce(nts: String) {
        val sock = socket ?: return
        val destination = InetSocketAddress(InetAddress.getByName(MULTICAST_ADDRESS), SSDP_PORT)
        for ((nt, usn) in targets) {
            val notify = buildString {
                append("NOTIFY * HTTP/1.1\r\n")
                append("HOST: ").append(MULTICAST_ADDRESS).append(':').append(SSDP_PORT).append("\r\n")
                append("CACHE-CONTROL: max-age=1800\r\n")
                append("LOCATION: ").append(location).append("\r\n")
                append("NT: ").append(nt).append("\r\n")
                append("NTS: ").append(nts).append("\r\n")
                append("SERVER: ").append(SERVER).append("\r\n")
                append("USN: ").append(usn).append("\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII)
            runCatching { sock.send(DatagramPacket(notify, notify.size, destination)) }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SsdpResponder::class.java)
        private const val MULTICAST_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private const val SERVICE_AVT = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val SERVICE_RC = "urn:schemas-upnp-org:service:RenderingControl:1"
        private const val SERVICE_CM = "urn:schemas-upnp-org:service:ConnectionManager:1"
        private const val SERVER = "Linux/1.0 UPnP/1.0 VibeView/1.0"
    }
}
