package io.ather.pro.data.analytics

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ather.pro.domain.analytics.VehicleHealthComponent
import io.ather.pro.domain.analytics.VehicleHealthMeta
import io.ather.pro.domain.analytics.VehicleHealthOverall
import io.ather.pro.domain.analytics.VehicleHealthReport
import io.ather.pro.domain.analytics.VehicleHealthResale
import io.ather.pro.domain.analytics.VehicleHealthTag
import io.ather.pro.domain.analytics.VehicleHealthVehicleInfo
import io.ather.pro.domain.analytics.VehicleWearItem

/**
 * Pure parser for Cerberus `GET /api/v1/vehicle-health/report?uuid=…`.
 * Envelope: `{ "data": { vehicle, health, meta, resale_estimation } }`.
 */
object VehicleHealthReportParser {

    fun parse(raw: String): VehicleHealthReport? {
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        val data = root.obj("data") ?: root
        val health = data.obj("health")
        val overall = health?.obj("overall")?.let { overallObj ->
            VehicleHealthOverall(
                score = overallObj.float("score"),
                label = overallObj.str("label"),
                maxScore = overallObj.int("max_score"),
                colorCode = overallObj.str("color_code")
            )
        }
        val components = health?.arr("components")?.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            VehicleHealthComponent(
                id = obj.str("id"),
                name = obj.str("name"),
                score = obj.float("score"),
                maxScore = obj.int("max_score"),
                tag = obj.obj("tag")?.toTag(),
                displayType = obj.str("display_type")
            )
        }.orEmpty()
        val wear = health?.arr("wear_and_tear_components")?.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            VehicleWearItem(
                id = obj.str("id"),
                name = obj.str("name"),
                lifePercent = obj.int("life_percent"),
                currentKms = obj.int("current_kms"),
                remainingKms = obj.int("remaining_kms"),
                tag = obj.obj("tag")?.toTag(),
                description = obj.obj("description")?.toTag()
            )
        }.orEmpty()
        val vehicle = data.obj("vehicle")?.let { v ->
            VehicleHealthVehicleInfo(
                id = v.str("id"),
                name = v.str("name"),
                registrationMasked = v.str("registration_number_masked") ?: v.str("registration_number"),
                ageYears = v.str("age_years"),
                odoKms = v.int("odo_kms"),
                odoFormatted = v.str("odo_formatted")
            )
        }
        val meta = data.obj("meta")?.let { m ->
            VehicleHealthMeta(
                lastUpdated = m.str("last_updated"),
                refreshNote = m.str("refresh_note")
            )
        }
        val resale = data.obj("resale_estimation")?.let { r ->
            VehicleHealthResale(
                currency = r.str("currency"),
                disclaimer = r.str("disclaimer"),
                minValue = r.int("min_value"),
                maxValue = r.int("max_value"),
                displayText = r.str("display_text")
            )
        }
        if (overall == null && components.isEmpty() && wear.isEmpty() && vehicle == null && resale == null) {
            return null
        }
        return VehicleHealthReport(
            vehicle = vehicle,
            overall = overall,
            components = components,
            wearAndTear = wear,
            resale = resale,
            meta = meta
        )
    }

    private fun JsonObject.toTag(): VehicleHealthTag = VehicleHealthTag(
        text = str("text"),
        textColor = str("text_color"),
        backgroundColor = str("background_color")
    )

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? {
        val el = get(key) ?: return null
        if (!el.isJsonPrimitive) return null
        val p = el.asJsonPrimitive
        return when {
            p.isNumber -> p.asInt
            p.isString -> p.asString.toIntOrNull()
            else -> null
        }
    }

    private fun JsonObject.float(key: String): Float? {
        val el = get(key) ?: return null
        if (!el.isJsonPrimitive) return null
        val p = el.asJsonPrimitive
        return when {
            p.isNumber -> p.asFloat
            p.isString -> p.asString.toFloatOrNull()
            else -> null
        }
    }
}
