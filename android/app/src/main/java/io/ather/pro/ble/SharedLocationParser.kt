package io.ather.pro.ble

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Parses locations from Google Maps shares — the entry points the real app
 * declares on its MainActivity (ACTION_SEND text share and geo: VIEW links):
 *
 *   geo:12.97,77.59 / geo:0,0?q=12.97,77.59(Label) / geo:0,0?q=Label
 *   https://maps.google.com/?q=12.97,77.59
 *   https://maps.google.com/place/Name/@12.97,77.59,17z
 *   https://www.google.com/maps/.../!3d12.97!4d77.59
 *   https://maps.app.goo.gl/XXXX (short link — resolved over the network)
 *   plain "12.97, 77.59" text
 */
object SharedLocationParser {

    data class Place(val label: String?, val lat: Double, val lng: Double) {
        override fun toString(): String = "${label ?: "shared point"} ($lat, $lng)"
    }

    fun parse(text: String): Place? {
        val trimmed = text.trim()
        if (trimmed.startsWith("geo:", ignoreCase = true)) return fromGeo(trimmed)
        return fromUrlLikeText(trimmed) ?: fromBareCoordinates(trimmed)
    }

    fun fromGeo(uri: String): Place? {
        val body = uri.substringAfter("geo:", "").substringBefore('?')
        // Coordinates may appear both in the path and in ?q=.
        val qPart = uri.substringAfter('?', "")
        val qCoords = coordFromQuery(qPart.substringBefore('(')).also { }
        val pathCoords = coordsIn(body)
        val label = qPart.substringAfter('(', "").substringBefore(')').takeIf { it.isNotBlank() }
            ?: decode(qPart.substringAfter("q=", "").substringBefore('&')).takeIf {
                it.isNotBlank() && coordsIn(it) == null
            }
        val c = qCoords ?: pathCoords ?: return null
        return Place(label, c.first, c.second)
    }

    fun fromUrl(url: String): Place? {
        val u = url.substringBefore('#')
        val marker = "!3d(-?\\d+\\.?\\d*)!4d(-?\\d+\\.?\\d*)".toRegex().find(u)
        if (marker != null) {
            val lat = marker.groupValues[1].toDoubleOrNull() ?: return null
            val lng = marker.groupValues[2].toDoubleOrNull() ?: return null
            return Place(placeName(u), lat, lng)
        }
        coordFromQuery(u)?.let { return Place(placeName(u), it.first, it.second) }
        atPattern.find(u)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull() ?: return null
            val lng = m.groupValues[2].toDoubleOrNull() ?: return null
            return Place(placeName(u), lat, lng)
        }
        return null
    }

    /** Follow a maps.app.goo.gl / goo.gl short link to its final URL and parse that. */
    suspend fun resolveShortLink(shortUrl: String): Place? = withContext(Dispatchers.IO) {
        runCatching {
            var url = shortUrl
            repeat(5) {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                val loc = when {
                    conn.responseCode in 300..399 -> conn.getHeaderField("Location")
                    else -> null
                }
                conn.disconnect()
                if (loc == null) return@runCatching fromUrl(url)
                url = if (loc.startsWith("http")) loc else URL(URL(url), loc).toString()
            }
            fromUrl(url)
        }.getOrNull()
    }

    // ---------------------------------------------------------------- internals

    private val atPattern = Regex("[/@](-?\\d{1,2}\\.\\d+),(-?\\d{1,3}\\.\\d+)")

    private fun fromUrlLikeText(text: String): Place? {
        val url = Regex("https?://\\S+").findAll(text).firstOrNull()?.value ?: return null
        val direct = fromUrl(url)
        if (direct != null) return direct
        if (url.contains("goo.gl", true) || url.contains("maps.app.goo", true)) return null // caller resolves
        return null
    }

    private fun fromBareCoordinates(text: String): Place? {
        val m = Regex("^(-?\\d{1,2}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)$").find(text.trim()) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lng = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return Place(null, lat, lng)
    }

    /** ?q=lat,lng (also %2C) anywhere in the string. */
    private fun coordFromQuery(s: String): Pair<Double, Double>? {
        val m = Regex("[?&](?:q|ll|center|query)=(-?\\d{1,2}\\.?\\d*)[,%2C]+(-?\\d{1,3}\\.?\\d*)", RegexOption.IGNORE_CASE).find(s)
            ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lng = m.groupValues[2].toDoubleOrNull() ?: return null
        return lat to lng
    }

    private fun coordsIn(s: String): Pair<Double, Double>? {
        val m = Regex("^(-?\\d{1,2}\\.\\d+),(-?\\d{1,3}\\.\\d+)$").find(s.trim()) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lng = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }

    /** Human label from /place/<Name>/ or ?query=<Name>. */
    private fun placeName(url: String): String? {
        Regex("/place/([^/@?]+)").find(url)?.let { return decode(it.groupValues[1]) }
        Regex("[?&](?:query|destination)=([^&]+)").find(url)?.let {
            val v = decode(it.groupValues[1])
            if (coordsIn(v) == null && coordFromQuery("?q=$v") == null) return v.takeIf(String::isNotBlank)
        }
        return null
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /** Is this text only a short link that needs network resolution? */
    fun needsResolution(text: String): Boolean {
        val url = Regex("https?://\\S+").find(text)?.value ?: return false
        return (url.contains("goo.gl", true)) && fromUrl(url) == null
    }

    fun firstUrl(text: String): String? = Regex("https?://\\S+").find(text)?.value
}
