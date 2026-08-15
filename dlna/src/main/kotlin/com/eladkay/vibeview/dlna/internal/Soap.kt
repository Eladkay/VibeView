package com.eladkay.vibeview.dlna.internal

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Minimal SOAP parsing/serialization for UPnP control requests. */
internal object Soap {

    data class Request(val action: String, val args: Map<String, String>)

    /** Parses a UPnP control request body into its action name and argument map. */
    fun parse(body: ByteArray): Request? {
        if (body.isEmpty()) return null
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(body))
            val bodyEl = findByLocalName(doc.documentElement, "Body") ?: return null
            val actionEl = firstChildElement(bodyEl) ?: return null
            val args = LinkedHashMap<String, String>()
            val children = actionEl.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node.nodeType == Node.ELEMENT_NODE) {
                    args[localName(node.nodeName)] = node.textContent ?: ""
                }
            }
            Request(localName(actionEl.nodeName), args)
        } catch (_: Exception) {
            null
        }
    }

    /** Builds a SOAP action response with the given out-arguments (in order). */
    fun response(serviceType: String, action: String, args: List<Pair<String, String>>): ByteArray {
        val sb = StringBuilder(256)
        sb.append("""<?xml version="1.0" encoding="utf-8"?>""")
        sb.append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """)
        sb.append("""s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
        sb.append("<u:").append(action).append("Response xmlns:u=\"").append(serviceType).append("\">")
        for ((name, value) in args) {
            sb.append('<').append(name).append('>')
            sb.append(UpnpTime.xmlEscape(value))
            sb.append("</").append(name).append('>')
        }
        sb.append("</u:").append(action).append("Response></s:Body></s:Envelope>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** A SOAP fault for an unsupported action (UPnP error 401). */
    fun fault(): ByteArray = ("""<?xml version="1.0" encoding="utf-8"?>""" +
        """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
        """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><s:Fault>""" +
        "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
        """<UPnPError xmlns="urn:schemas-upnp-org:control-1-0">""" +
        "<errorCode>401</errorCode><errorDescription>Invalid Action</errorDescription>" +
        "</UPnPError></detail></s:Fault></s:Body></s:Envelope>").toByteArray(Charsets.UTF_8)

    private fun localName(nodeName: String): String = nodeName.substringAfterLast(':')

    private fun findByLocalName(root: Element, name: String): Element? {
        val queue = ArrayDeque<Node>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node is Element && localName(node.nodeName) == name) return node
            val children = node.childNodes
            for (i in 0 until children.length) queue.add(children.item(i))
        }
        return null
    }

    private fun firstChildElement(parent: Element): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element) return node
        }
        return null
    }
}
