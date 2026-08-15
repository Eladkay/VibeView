package com.eladkay.vibeview.dlna.internal

import com.eladkay.vibeview.dlna.DlnaRendererListener

/** Dispatches a parsed SOAP request to the listener and builds the response body. */
internal object UpnpActions {

    fun handle(serviceType: String, request: Soap.Request, listener: DlnaRendererListener): ByteArray {
        val a = request.args
        return when (serviceType) {
            UpnpDevice.SERVICE_AVT -> handleAvTransport(request.action, a, listener)
            UpnpDevice.SERVICE_RC -> handleRenderingControl(request.action, a, listener)
            UpnpDevice.SERVICE_CM -> handleConnectionManager(request.action)
            else -> Soap.fault()
        }
    }

    private fun handleAvTransport(action: String, a: Map<String, String>, listener: DlnaRendererListener): ByteArray {
        return when (action) {
            "SetAVTransportURI" -> {
                val uri = a["CurrentURI"]?.trim().orEmpty()
                if (uri.isNotEmpty()) listener.onSetUri(uri, a["CurrentURIMetaData"])
                ok(UpnpDevice.SERVICE_AVT, action)
            }
            "SetNextAVTransportURI" -> {
                val uri = a["NextURI"]?.trim().orEmpty()
                listener.onSetNextUri(uri.ifEmpty { null }, a["NextURIMetaData"])
                ok(UpnpDevice.SERVICE_AVT, action)
            }
            "Play" -> { listener.onPlay(); ok(UpnpDevice.SERVICE_AVT, action) }
            "Pause" -> { listener.onPause(); ok(UpnpDevice.SERVICE_AVT, action) }
            "Stop" -> { listener.onStop(); ok(UpnpDevice.SERVICE_AVT, action) }
            "Seek" -> {
                val unit = a["Unit"]?.uppercase()
                if (unit == "REL_TIME" || unit == "ABS_TIME") {
                    listener.onSeekSeconds(UpnpTime.parse(a["Target"].orEmpty()))
                }
                ok(UpnpDevice.SERVICE_AVT, action)
            }
            "GetPositionInfo" -> {
                val s = listener.status()
                Soap.response(
                    UpnpDevice.SERVICE_AVT, action, listOf(
                        "Track" to "1",
                        "TrackDuration" to UpnpTime.format(s.durationSeconds),
                        "TrackMetaData" to "NOT_IMPLEMENTED",
                        "TrackURI" to (s.uri ?: ""),
                        "RelTime" to UpnpTime.format(s.positionSeconds),
                        "AbsTime" to UpnpTime.format(s.positionSeconds),
                        "RelCount" to "2147483647",
                        "AbsCount" to "2147483647",
                    )
                )
            }
            "GetTransportInfo" -> {
                val s = listener.status()
                Soap.response(
                    UpnpDevice.SERVICE_AVT, action, listOf(
                        "CurrentTransportState" to s.state.upnpName,
                        "CurrentTransportStatus" to "OK",
                        "CurrentSpeed" to "1",
                    )
                )
            }
            "GetMediaInfo" -> {
                val s = listener.status()
                Soap.response(
                    UpnpDevice.SERVICE_AVT, action, listOf(
                        "NrTracks" to if (s.uri != null) "1" else "0",
                        "MediaDuration" to UpnpTime.format(s.durationSeconds),
                        "CurrentURI" to (s.uri ?: ""),
                        "CurrentURIMetaData" to "",
                        "NextURI" to "",
                        "NextURIMetaData" to "",
                        "PlayMedium" to "NETWORK",
                        "RecordMedium" to "NOT_IMPLEMENTED",
                        "WriteStatus" to "NOT_IMPLEMENTED",
                    )
                )
            }
            "GetTransportSettings" -> Soap.response(
                UpnpDevice.SERVICE_AVT, action, listOf(
                    "PlayMode" to "NORMAL",
                    "RecQualityMode" to "NOT_IMPLEMENTED",
                )
            )
            "GetDeviceCapabilities" -> Soap.response(
                UpnpDevice.SERVICE_AVT, action, listOf(
                    "PlayMedia" to "NETWORK,HTTP-GET",
                    "RecMedia" to "NOT_IMPLEMENTED",
                    "RecQualityModes" to "NOT_IMPLEMENTED",
                )
            )
            else -> Soap.fault()
        }
    }

    private fun handleRenderingControl(action: String, a: Map<String, String>, listener: DlnaRendererListener): ByteArray {
        return when (action) {
            "GetVolume" -> Soap.response(
                UpnpDevice.SERVICE_RC, action, listOf("CurrentVolume" to listener.status().volume.toString())
            )
            "SetVolume" -> {
                a["DesiredVolume"]?.toIntOrNull()?.let { listener.onSetVolume(it.coerceIn(0, 100)) }
                ok(UpnpDevice.SERVICE_RC, action)
            }
            "GetMute" -> Soap.response(UpnpDevice.SERVICE_RC, action, listOf("CurrentMute" to "0"))
            "SetMute" -> ok(UpnpDevice.SERVICE_RC, action)
            else -> Soap.fault()
        }
    }

    private fun handleConnectionManager(action: String): ByteArray {
        return when (action) {
            "GetProtocolInfo" -> Soap.response(
                UpnpDevice.SERVICE_CM, action, listOf(
                    "Source" to "",
                    "Sink" to UpnpDevice.SINK_PROTOCOL_INFO,
                )
            )
            "GetCurrentConnectionIDs" -> Soap.response(
                UpnpDevice.SERVICE_CM, action, listOf("ConnectionIDs" to "0")
            )
            "GetCurrentConnectionInfo" -> Soap.response(
                UpnpDevice.SERVICE_CM, action, listOf(
                    "RcsID" to "0",
                    "AVTransportID" to "0",
                    "ProtocolInfo" to "",
                    "PeerConnectionManager" to "",
                    "PeerConnectionID" to "-1",
                    "Direction" to "Input",
                    "Status" to "OK",
                )
            )
            else -> Soap.fault()
        }
    }

    private fun ok(serviceType: String, action: String): ByteArray =
        Soap.response(serviceType, action, emptyList())
}
