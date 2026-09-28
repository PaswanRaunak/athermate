package io.ather.pro.data.auth

import android.util.Base64
import org.json.JSONObject

object JwtExpiry {
    fun expiresAtEpochSec(token: String): Long? {
        val parts = token.split('.')
        if (parts.size < 2) return null
        return runCatching {
            val padded = padBase64(parts[1])
            val json = String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP))
            val exp = JSONObject(json).optLong("exp", -1L)
            exp.takeIf { it > 0L }
        }.getOrNull()
    }

    private fun padBase64(value: String): String {
        val pad = (4 - value.length % 4) % 4
        return value + "=".repeat(pad)
    }
}
