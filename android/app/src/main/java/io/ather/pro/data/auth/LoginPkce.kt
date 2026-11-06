package io.ather.pro.data.auth

import android.util.Base64
import java.security.SecureRandom
import java.security.MessageDigest

/**
 * New Ather login protocol (official 13.6.0, api/v2/req endpoints) — PKCE-protected OTP.
 *
 * Flow: generate a code_verifier, derive the S256 code_challenge, send it with
 * the OTP request, prove possession with the verifier on verify.
 *
 * GenerateLoginOTP: POST https://cerberus.ather.io/api/v2/req/8h91
 *   {"email":"","contact_no":"…","country_code":"IN",
 *    "notification_medium":{"whatsapp":false,"sms":true},
 *    "code_challenge":"…","code_challenge_method":"S256"}
 * VerifyLoginOTP:   POST https://cerberus.ather.io/api/v2/req/8h71
 *   {"email":"","contact_no":"…","is_mobile_login":true,"country_code":"IN",
 *    "grant_type":"…","client_id":"…","code_verifier":"…"}
 *
 * Both endpoints are behind Firebase App Check (X-Firebase-AppCheck header):
 * 401/1013 without a token, 401/1014 with an invalid one. Token minting
 * requires the Firebase project wiring (google-services.json) — see
 * docs/AUTH-MIGRATION.md.
 */
object LoginPkce {

    data class Pair(val verifier: String, val challenge: String)

    private val random = SecureRandom()

    /** 43-128 chars from the unreserved set, per RFC 7636. */
    fun newPair(): Pair {
        val bytes = ByteArray(48).also { random.nextBytes(it) }
        val verifier = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            .trimEnd('=')
        return Pair(verifier, s256Challenge(verifier))
    }

    /** code_challenge = BASE64URL-ENCODE(SHA256(ASCII(code_verifier))) */
    fun s256Challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            .trimEnd('=')
    }
}
