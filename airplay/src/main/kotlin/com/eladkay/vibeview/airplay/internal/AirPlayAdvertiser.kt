package com.eladkay.vibeview.airplay.internal

import com.eladkay.vibeview.airplay.AirPlayConfig
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Registers the `_airplay._tcp` and `_raop._tcp` Bonjour services on the active
 * LAN interface so Apple devices can discover the receiver.
 */
internal class AirPlayAdvertiser(
    private val config: AirPlayConfig,
    private val publicKeyHex: String,
) {

    private var jmdns: JmDNS? = null

    fun start(address: InetAddress? = null) {
        val bindAddress = address ?: pickAddress()
            ?: throw IllegalStateException("No usable network interface for Bonjour advertising")
        val dns = JmDNS.create(bindAddress, config.serverName)
        jmdns = dns

        val airplayService = ServiceInfo.create(
            "_airplay._tcp.local.", config.serverName, config.airplayPort, 0, 0, airplayProps()
        )
        dns.registerService(airplayService)

        val raopName = config.deviceId.replace(":", "") + "@" + config.serverName
        val raopService = ServiceInfo.create(
            "_raop._tcp.local.", raopName, config.airtunesPort, 0, 0, raopProps()
        )
        dns.registerService(raopService)

        log.info(
            "Advertising '{}' on {} (_airplay._tcp:{}, _raop._tcp:{})",
            config.serverName, bindAddress.hostAddress, config.airplayPort, config.airtunesPort
        )
    }

    fun stop() {
        jmdns?.let {
            runCatching {
                it.unregisterAllServices()
                it.close()
            }
        }
        jmdns = null
    }

    private fun pickAddress(): InetAddress? =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
            .sortedBy { nic -> if (nic.name.startsWith("wlan") || nic.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }

    private val passwordProtected: String
        get() = if (!config.password.isNullOrEmpty()) "true" else "false"

    private fun airplayProps(): Map<String, String> = mapOf(
        "deviceid" to config.deviceId,
        "features" to FEATURES,
        "srcvers" to InfoResponse.SOURCE_VERSION,
        "flags" to "0x4",
        "vv" to "2",
        "model" to InfoResponse.MODEL,
        "pw" to passwordProtected,
        "rhd" to "5.6.0.0",
        "pk" to publicKeyHex,
        "pi" to config.pairingId,
    )

    private fun raopProps(): Map<String, String> = mapOf(
        "ch" to "2",
        "cn" to "0,1,2,3",
        "da" to "true",
        "et" to "0,3,5",
        "vv" to "2",
        "ft" to FEATURES,
        "am" to InfoResponse.MODEL,
        "md" to "0,1,2",
        "rhd" to "5.6.0.0",
        "pw" to passwordProtected,
        "sr" to "44100",
        "ss" to "16",
        "sv" to "false",
        "tp" to "UDP",
        "txtvers" to "1",
        "sf" to "0x4",
        "vs" to InfoResponse.SOURCE_VERSION,
        "vn" to "65537",
        "pk" to publicKeyHex,
    )

    companion object {
        private val log = LoggerFactory.getLogger(AirPlayAdvertiser::class.java)
        private const val FEATURES = "0x5A7FFFF7,0x1E"
    }
}
