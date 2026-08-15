package com.eladkay.vibeview.dlna

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Title and subtitle tracks pulled from a control point's DIDL-Lite metadata. */
data class MediaMetadata(
    val title: String? = null,
    val subtitleUrls: List<String> = emptyList(),
)

/**
 * Extracts the useful parts of the DIDL-Lite document control points send alongside
 * a media URL.
 *
 * Subtitles are advertised inconsistently in the wild: as a `<res>` whose
 * `protocolInfo` names a subtitle MIME type, or via Samsung's widely-copied
 * `sec:CaptionInfo`/`sec:CaptionInfoEx` elements. Both are handled.
 */
object DidlLite {

    private val SUBTITLE_HINTS = listOf("srt", "smi", "ssa", "ass", "sub", "vtt", "text/")

    fun parse(metadata: String?): MediaMetadata? {
        if (metadata.isNullOrBlank()) return null
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val document = factory.newDocumentBuilder()
                .parse(ByteArrayInputStream(metadata.toByteArray(Charsets.UTF_8)))

            val title = elements(document.documentElement)
                .firstOrNull { local(it.nodeName) == "title" }
                ?.textContent
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            val subtitles = LinkedHashSet<String>()
            for (element in elements(document.documentElement)) {
                val name = local(element.nodeName)
                val text = element.textContent?.trim().orEmpty()
                if (text.isEmpty() || !text.startsWith("http", ignoreCase = true)) continue
                when {
                    name.startsWith("CaptionInfo", ignoreCase = true) -> subtitles.add(text)
                    name == "res" -> {
                        val protocolInfo = element.getAttribute("protocolInfo").lowercase()
                        if (SUBTITLE_HINTS.any { protocolInfo.contains(it) }) subtitles.add(text)
                    }
                }
            }
            if (title == null && subtitles.isEmpty()) null
            else MediaMetadata(title, subtitles.toList())
        } catch (_: Exception) {
            null
        }
    }

    private fun local(nodeName: String) = nodeName.substringAfterLast(':')

    private fun elements(root: Element): Sequence<Element> = sequence {
        val queue = ArrayDeque<Element>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val element = queue.removeFirst()
            yield(element)
            val children = element.childNodes
            for (i in 0 until children.length) {
                (children.item(i) as? Element)?.let { queue.add(it) }
            }
        }
    }
}
