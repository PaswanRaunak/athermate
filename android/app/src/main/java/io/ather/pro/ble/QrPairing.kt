package io.ather.pro.ble

/**
 * Parses a scooter QR payload into pairing info. The vehicle's QR format is not
 * documented, so this accepts the plausible shapes: a bare MAC address, JSON with
 * address/name-ish fields, or a URL with mac/address query parameters. Anything
 * else surfaces the raw text so an unknown format can still be reported and added.
 */
object QrPairing {

    data class Payload(val address: String?, val name: String?, val raw: String) {
        val recognised: Boolean get() = address != null || !name.isNullOrBlank()
    }

    private val MAC = Regex("""\b([0-9A-Fa-f]{2}[:\-]){5}[0-9A-Fa-f]{2}\b""")

    private val ADDRESS_KEYS = setOf("address", "mac", "macaddress", "bt", "ble", "deviceaddress", "deviceid", "id")
    private val NAME_KEYS = setOf("name", "devicename", "device_name", "scooter", "vehicle")

    fun parse(text: String): Payload {
        val raw = text.trim()
        val decoded = raw
            .replace(Regex("%3A", RegexOption.IGNORE_CASE), ":")
            .replace(Regex("%2D", RegexOption.IGNORE_CASE), "-")
        val address = MAC.find(raw)?.value
            ?: MAC.find(decoded)?.value
            ?: fieldFromJson(decoded, ADDRESS_KEYS)?.let(MAC::matchEntire)?.value
            ?: paramFromUrl(decoded, ADDRESS_KEYS)?.let(MAC::matchEntire)?.value
        val name = fieldFromJson(decoded, NAME_KEYS)
            ?: paramFromUrl(decoded, NAME_KEYS)
        return Payload(
            address = address?.uppercase()?.replace('-', ':'),
            name = name?.trim()?.takeIf(String::isNotBlank),
            raw = raw
        )
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
