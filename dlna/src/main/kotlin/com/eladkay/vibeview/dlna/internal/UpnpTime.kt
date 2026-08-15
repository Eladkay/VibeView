package com.eladkay.vibeview.dlna.internal

/** UPnP time-string (`H:MM:SS`) and XML-escaping helpers. */
internal object UpnpTime {

    /** Formats seconds as the UPnP `H:MM:SS` duration/position string. */
    fun format(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return "%d:%02d:%02d".format(h, m, s)
    }

    /** Parses a UPnP `H:MM:SS(.frac)` time string to seconds; 0 on malformed input. */
    fun parse(value: String): Double {
        val parts = value.trim().split(':')
        if (parts.isEmpty()) return 0.0
        return try {
            when (parts.size) {
                3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toDouble()
                2 -> parts[0].toLong() * 60 + parts[1].toDouble()
                1 -> parts[0].toDouble()
                else -> 0.0
            }
        } catch (_: NumberFormatException) {
            0.0
        }
    }

    fun xmlEscape(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
