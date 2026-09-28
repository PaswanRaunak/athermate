package io.ather.pro.data.chargingmap

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ather.pro.domain.chargingmap.ChargerConnector
import io.ather.pro.domain.chargingmap.ChargerLocation
import io.ather.pro.domain.chargingmap.TariffLine
import io.ather.pro.domain.chargingmap.WalletSnapshot
import io.ather.pro.domain.chargingmap.WalletTransaction

/**
 * Pure parsers for Cerberus public-charger and wallet JSON.
 * Matches Mether field names; optional credits/transactions only when present.
 */
object ChargingMapParser {

    fun parseLocations(json: String): List<ChargerLocation> {
        val root = JsonParser.parseString(json)
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> {
                val obj = root.asJsonObject
                obj.arrayOrNull("data")
                    ?: obj.arrayOrNull("locations")
                    ?: obj.arrayOrNull("results")
                    ?: JsonArray()
            }
            else -> JsonArray()
        }
        return array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            parseLocation(obj)
        }
    }

    fun parseWallet(json: String): WalletSnapshot {
        val root = JsonParser.parseString(json).asJsonObject
        val data = root.objectOrNull("data") ?: root
        return WalletSnapshot(
            balance = data.numberOrNull("balance", "wallet_balance", "available_balance"),
            walletStatus = data.text("wallet_status", "status", "walletStatus"),
            credits = data.numberOrNull("credits", "charging_credits", "available_credits"),
            transactions = parseTransactions(
                data.arrayOrNull("transactions")
                    ?: data.arrayOrNull("recent_transactions")
                    ?: root.arrayOrNull("transactions")
            ),
        )
    }

    private fun parseLocation(obj: JsonObject): ChargerLocation? {
        val lat = obj.numberOrNull("latitude", "lat")
        val lng = obj.numberOrNull("longitude", "lng", "lon")
        // Drop entries with no usable coordinates — map markers require both.
        if (lat == null || lng == null) return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null

        val connectors = (obj.arrayOrNull("connector_type") ?: obj.arrayOrNull("connectors"))
            ?.mapNotNull { el ->
                val c = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                ChargerConnector(
                    displayText = c.text("display_text", "displayText", "name"),
                    standard = c.text("standard", "type"),
                )
            }
            .orEmpty()

        val tariffLines = obj.objectOrNull("tariff_details")
            ?.arrayOrNull("line_items")
            ?.mapNotNull { el ->
                val t = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                TariffLine(
                    displayText = t.text("display_text", "displayText"),
                    text = t.text("text", "value"),
                )
            }
            .orEmpty()

        val tags = obj.arrayOrNull("location_tags")
            ?.mapNotNull { el ->
                el.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
            }
            .orEmpty()

        return ChargerLocation(
            name = obj.text("name"),
            infraType = obj.text("infra_type", "infraType"),
            address = obj.text("address_1", "address", "address1"),
            latitude = lat,
            longitude = lng,
            isOpenNow = obj.booleanOrNull("is_open_now", "isOpenNow"),
            closingIn = obj.text("closing_in", "closingIn"),
            nextOpening = obj.text("next_opening", "nextOpening"),
            locationTags = tags,
            dbsAvailable = obj.intOrNull("dbs_available", "dbsAvailable"),
            dbsInUse = obj.intOrNull("dbs_in_use", "dbsInUse"),
            dbsUnderMaintenance = obj.intOrNull("dbs_under_maintenance", "dbsUnderMaintenance"),
            dbsOutOfOperatingHours = obj.intOrNull(
                "dbs_out_of_operating_hours",
                "dbsOutOfOperatingHours",
            ),
            dbsTotal = obj.intOrNull("dbs_total", "dbsTotal"),
            connectors = connectors,
            tariffLines = tariffLines,
            partyId = obj.text("party_id", "partyId"),
            has6kwGrid = obj.booleanOrNull("has_6kW_grid", "has_6kw_grid", "has6kwGrid"),
        )
    }

    private fun parseTransactions(array: JsonArray?): List<WalletTransaction> {
        if (array == null || array.size() == 0) return emptyList()
        return array.mapNotNull { el ->
            val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            WalletTransaction(
                id = obj.text("id", "transaction_id", "txn_id"),
                title = obj.text("title", "description", "narration", "name"),
                amount = obj.numberOrNull("amount", "value"),
                currency = obj.text("currency", "currency_code"),
                timestamp = obj.text("timestamp", "created_at", "time", "date"),
                type = obj.text("type", "txn_type", "direction"),
            )
        }
    }

    private fun JsonObject?.objectOrNull(key: String): JsonObject? =
        this?.get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

    private fun JsonObject?.arrayOrNull(key: String): JsonArray? =
        this?.get(key)?.takeIf(JsonElement::isJsonArray)?.asJsonArray

    private fun JsonObject?.text(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            this?.get(key)
                ?.takeIf { it.isJsonPrimitive && !it.isJsonNull }
                ?.asString
                ?.takeIf(String::isNotBlank)
        }

    private fun JsonObject?.numberOrNull(vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { key ->
            val el = this?.get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull } ?: return@firstNotNullOfOrNull null
            when {
                el.asJsonPrimitive.isNumber -> el.asDouble
                el.asJsonPrimitive.isString -> el.asString.toDoubleOrNull()
                else -> null
            }
        }

    private fun JsonObject?.intOrNull(vararg keys: String): Int? =
        numberOrNull(*keys)?.toInt()

    private fun JsonObject?.booleanOrNull(vararg keys: String): Boolean? =
        keys.firstNotNullOfOrNull { key ->
            val el = this?.get(key)?.takeIf { !it.isJsonNull } ?: return@firstNotNullOfOrNull null
            when {
                el.isJsonPrimitive && el.asJsonPrimitive.isBoolean -> el.asBoolean
                el.isJsonPrimitive && el.asJsonPrimitive.isString -> when (el.asString.lowercase()) {
                    "true", "1", "yes", "on" -> true
                    "false", "0", "no", "off" -> false
                    else -> null
                }
                el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asInt != 0
                else -> null
            }
        }
}
