package io.ather.pro.ble

/**
 * Parses a scooter QR payload into pairing info. Accepts MAC addresses, JSON with
 * address/name/token fields, URLs with mac/address/token query parameters, the
 * Ather dash QR URL (https://app.atherenergy.com/bluetooth/scan/<token>), or bare
 * 8-character pairing tokens shown on the vehicle dash.
 *
 * Parsing is pure string work (no android.net.Uri) so the exact device code path
 * is covered by JVM unit tests.
 */
object QrPairing {

    data class Payload(val address: String?, val name: String?, val token: String?, val raw: String) {
        val recognised: Boolean get() = address != null || !name.isNullOrBlank() || !token.isNullOrBlank()
    }

    private val MAC = Regex("""\b([0-9A-Fa-f]{2}[:\-]){5}[0-9A-Fa-f]{2}\b""")

    private val ADDRESS_KEYS = setOf("address", "mac", "macaddress", "bt", "ble", "deviceaddress", "deviceid", "id")
    private val NAME_KEYS = setOf("name", "devicename", "device_name", "scooter", "vehicle")
    private val TOKEN_KEYS = setOf("token", "pairingtoken", "pairtoken", "code", "qrtoken")

    fun parse(text: String): Payload {
        val raw = text.trim()
        val decoded = raw
            .replace(Regex("%3A", RegexOption.IGNORE_CASE), ":")
            .replace(Regex("%2D", RegexOption.IGNORE_CASE), "-")
        val token = paramFromUrl(decoded, TOKEN_KEYS)
            ?: fieldFromJson(decoded, TOKEN_KEYS)
            ?: extractTokenFromUriOrString(decoded)
        val address = MAC.find(raw)?.value
            ?: MAC.find(decoded)?.value
            ?: fieldFromJson(decoded, ADDRESS_KEYS)?.let(MAC::matchEntire)?.value
            ?: paramFromUrl(decoded, ADDRESS_KEYS)?.let(MAC::matchEntire)?.value
        val name = fieldFromJson(decoded, NAME_KEYS)
            ?: paramFromUrl(decoded, NAME_KEYS)
        return Payload(
            address = address?.uppercase()?.replace('-', ':'),
            name = name?.trim()?.takeIf(String::isNotBlank),
            token = token?.trim()?.takeIf(String::isNotBlank),
            raw = raw
        )
    }

    /**
     * Tokens hidden in URL paths: the real dash QR encodes
     * https://app.atherenergy.com/bluetooth/scan/<token> (also seen as /pair/).
     * Handled with plain string operations so JVM tests run the same path.
     */
    private fun extractTokenFromUriOrString(text: String): String? {
        val lowered = text.lowercase()
        val marker = "/bluetooth/"
        val markerIndex = lowered.indexOf(marker)
        if (markerIndex >= 0) {
            val after = text.substring(markerIndex + marker.length).substringBefore('#')
            val query = if (after.contains('?')) after.substringAfter('?') else ""
            var path = if (after.contains('?')) after.substringBefore('?') else after
            path = path.trim('/')
            val reserved = setOf("pair", "scan", "bluetooth", "")
            if (path.isNotBlank() && path.lowercase() !in reserved) {
                val last = path.substringAfterLast('/')
                if (last.isNotBlank() && last.lowercase() !in reserved) return last
            }
            Regex("""token=([^&\s]+)""", RegexOption.IGNORE_CASE).find(query)?.let { return it.groupValues[1] }
        }
        // Generic ?token=… anywhere else in the URL.
        Regex("""[?&]token=([^&\s]+)""", RegexOption.IGNORE_CASE).find(text)?.let { return it.groupValues[1] }
        // Bare token printed on the dash (8 chars, no separators).
        if (Regex("""^[A-Za-z0-9_-]{3,32}$""").matches(text)) return text
        return null
    }

    /** "key":"value" pairs — tolerant scan without pulling a JSON dependency. */
    private fun fieldFromJson(text: String, keys: Set<String>): String? =
        Regex(""""([^"]+)"\s*:\s*"([^"]+)"""").findAll(text)
            .firstOrNull { it.groupValues[1].lowercase().replace("_", "").replace("-", "") in keys }
            ?.groupValues?.get(2)

    private fun paramFromUrl(text: String, keys: Set<String>): String? {
        val query = text.substringBefore('#').substringAfter('?', "")
        if (query == text) return null
        return query.split('&')
            .firstOrNull { it.substringBefore('=').lowercase().replace("_", "").replace("-", "") in keys }
            ?.substringAfter('=', "")
            ?.takeIf(String::isNotBlank)
    }
}
