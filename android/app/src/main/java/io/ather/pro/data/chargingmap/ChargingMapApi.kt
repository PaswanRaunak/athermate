package io.ather.pro.data.chargingmap

import android.os.Build
import io.ather.pro.domain.chargingmap.ChargerLocation
import io.ather.pro.domain.chargingmap.WalletSnapshot
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cerberus public-charger + wallet HTTP client (Mether contracts).
 *
 * - GET `/api/v2/locations?lat=&lng=&radius=&limit=&type=public`
 * - GET `/api/v1/wallet`
 *
 * Caller supplies the encrypted-session JWT. No credentials are stored here.
 * Location queries require a valid lat/lng; nothing is fabricated on failure.
 */
class ChargingMapApi(
    private val client: OkHttpClient = defaultClient(),
) {
    data class LocationsQuery(
        val latitude: Double,
        val longitude: Double,
        val radiusKm: Int = DEFAULT_RADIUS_KM,
        val limit: Int = DEFAULT_LIMIT,
        val parkingType: String? = null,
    )

    fun fetchLocations(token: String, query: LocationsQuery): Result<List<ChargerLocation>> {
        if (token.isBlank()) return Result.failure(IOException("Missing session token"))
        if (query.latitude !in -90.0..90.0 || query.longitude !in -180.0..180.0) {
            return Result.failure(IOException("Invalid map center"))
        }
        val radius = query.radiusKm.coerceIn(1, MAX_RADIUS_KM)
        val limit = query.limit.coerceIn(1, MAX_LIMIT)
        val url = buildString {
            append(LOCATIONS_URL)
            append("?lat=").append(query.latitude)
            append("&lng=").append(query.longitude)
            append("&radius=").append(radius)
            append("&limit=").append(limit)
            append("&type=public")
            val parking = query.parkingType?.trim().orEmpty()
            if (parking.isNotEmpty() && !parking.equals("ALL", ignoreCase = true)) {
                append("&parking_type=").append(parking)
            }
        }
        return execute(token, url) { body ->
            ChargingMapParser.parseLocations(body)
        }
    }

    fun fetchWallet(token: String): Result<WalletSnapshot> {
        if (token.isBlank()) return Result.failure(IOException("Missing session token"))
        return execute(token, WALLET_URL) { body ->
            ChargingMapParser.parseWallet(body)
        }
    }

    private fun <T> execute(token: String, url: String, parse: (String) -> T): Result<T> {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .atherHeaders(token)
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return Result.failure(
                        IOException("HTTP ${response.code}: ${response.message.ifBlank { "request failed" }}")
                    )
                }
                if (body.isBlank()) {
                    return Result.failure(IOException("Empty response"))
                }
                Result.success(parse(body))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun Request.Builder.atherHeaders(token: String): Request.Builder {
        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        val release = Build.VERSION.RELEASE ?: "unknown"
        addHeader("Authorization", "Bearer $token")
        addHeader("X-Request-Source", "ATHER_APP")
        addHeader("Accept-Charset", "UTF-8")
        addHeader("X-Platform", "Android")
        addHeader("X-Platform-Version", release)
        addHeader("X-Device-Info", device)
        addHeader("User-Agent", "Android/$release ($device)")
        addHeader("Accept", "application/json")
        addHeader("Content-Type", "application/json")
        return this
    }

    companion object {
        const val LOCATIONS_URL = "https://cerberus.ather.io/api/v2/locations"
        const val WALLET_URL = "https://cerberus.ather.io/api/v1/wallet"
        const val DEFAULT_RADIUS_KM = 5
        const val DEFAULT_LIMIT = 50
        const val MAX_RADIUS_KM = 50
        const val MAX_LIMIT = 200

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
    }
}
