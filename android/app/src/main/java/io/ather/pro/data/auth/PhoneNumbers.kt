package io.ather.pro.data.auth

import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

/** Country metadata and normalization shared by the form and API submission. */
object PhoneNumbers {
    private val util = PhoneNumberUtil.getInstance()
    data class Country(val region: String, val name: String, val dialCode: Int) {
        val label: String get() = "$name (+$dialCode)"
    }

    val countries: List<Country> by lazy {
        util.supportedRegions.map { region ->
            Country(region, Locale("", region).displayCountry, util.getCountryCodeForRegion(region))
        }.sortedBy { it.name }
    }

    fun country(region: String): Country = countries.firstOrNull { it.region == region }
        ?: countries.first { it.region == "IN" }

    data class Number(val region: String, val national: String)

    fun normalize(input: String, region: String): Number? = runCatching {
        val parsed = util.parse(input, region)
        if (!util.isValidNumber(parsed) || parsed.hasExtension()) return null
        // Keep the selected region for countries sharing a calling code (e.g. US/CA).
        val resolved = if (parsed.countryCode == util.getCountryCodeForRegion(region)) region
            else util.getRegionCodeForNumber(parsed) ?: return null
        if (resolved !in util.supportedRegions) return null
        Number(resolved, util.getNationalSignificantNumber(parsed))
    }.getOrNull()

    fun canSubmit(input: String, region: String): Boolean = normalize(input, region) != null
}
