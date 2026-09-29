package io.ather.pro.data.analytics

import io.ather.pro.domain.analytics.ScorecardState
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fetches Ather True Health Scorecard from the verified Cerberus endpoint.
 * 404 / empty body → [ScorecardState.Unavailable]. Never invents scores.
 */
class VehicleHealthReportClient(
    private val http: OkHttpClient = defaultClient()
) {
    fun fetch(
        token: String,
        scooterUuid: String,
        callback: (ScorecardState) -> Unit
    ) {
        if (token.isBlank() || scooterUuid.isBlank()) {
            callback(
                ScorecardState.Unavailable(
                    "Sign-in token or scooter UUID missing — cannot fetch scorecard."
                )
            )
            return
        }
        val request = Request.Builder()
            .url("$BASE/api/v1/vehicle-health/report?uuid=$scooterUuid")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("X-Request-Source", "ATHER_APP")
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .get()
            .build()

        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(ScorecardState.Error(e.message ?: "Network error"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    when (it.code) {
                        404 -> {
                            callback(
                                ScorecardState.Unavailable(
                                    "No scorecard supplied (HTTP 404). This is not battery SoH."
                                )
                            )
                            return
                        }
                        in 200..299 -> {
                            val body = it.body?.string().orEmpty()
                            if (body.isBlank()) {
                                callback(
                                    ScorecardState.Unavailable(
                                        "Empty vehicle-health body returned. This is not battery SoH."
                                    )
                                )
                                return
                            }
                            val report = VehicleHealthReportParser.parse(body)
                            callback(
                                if (report != null) ScorecardState.Available(report)
                                else ScorecardState.Unavailable(
                                    "Response could not be parsed as a vehicle-health report. This is not battery SoH."
                                )
                            )
                        }
                        else -> callback(ScorecardState.Error("HTTP ${it.code}"))
                    }
                }
            }
        })
    }

    /** Synchronous helper for deterministic unit tests. */
    internal fun parseBodyOrUnavailable(httpCode: Int, body: String?): ScorecardState {
        if (httpCode == 404) {
            return ScorecardState.Unavailable(
                "No scorecard supplied (HTTP 404). This is not battery SoH."
            )
        }
        if (httpCode !in 200..299) return ScorecardState.Error("HTTP $httpCode")
        if (body.isNullOrBlank()) {
            return ScorecardState.Unavailable(
                "Empty vehicle-health body returned. This is not battery SoH."
            )
        }
        val report = VehicleHealthReportParser.parse(body)
            ?: return ScorecardState.Unavailable(
                "Response could not be parsed as a vehicle-health report. This is not battery SoH."
            )
        return ScorecardState.Available(report)
    }

    companion object {
        private const val BASE = "https://cerberus.ather.io"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
