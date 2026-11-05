# Ather Login Migration — what changed in the official app (13.6.0)

Reverse-engineered from `com.athermobileapp` 13.6.0 (versionCode 327), extracted
from the Play XAPK. This documents why fresh logins stopped working in AtherMate
and what the new protocol looks like, so future work has a map.

## TL;DR

- The legacy OTP endpoints (`cerberus.ather.io/auth/v2/generate-login-otp`,
  `auth/v2/verify-login-otp`) return **HTTP 410 Gone** with
  `{"message":"Outdated App version. Update app to login.","error":"failure"}`.
  No header combination revives them — the flow was retired server-side.
- The replacement endpoints exist (`auth/generate-otp`, `user/generate-otp`,
  `user/verify-otp` on cerberus) but **enforce Firebase App Check**:
  - no `X-Firebase-AppCheck` header → `401 Unauthorized: Full authentication is required`
  - garbage header → `403`
  (verified live with curl; the code pair is Firebase App Check's standard
  Spring filter behavior)
- App Check tokens are minted by Google with **Play Integrity** attestation and
  are bound to the official package (`com.athermobileapp`) + signing cert.
  A third-party app cannot mint one. The official app itself sends requests
  header-less when token minting fails (the plugin never blocks).

## The new flow (official 13.6.0)

1. App generates a **PKCE** pair (`code_verifier` + `code_challenge`).
2. `POST https://cerberus.ather.io/auth/generate-otp` — body
   `GenerateOTPRequest`: `{"contactNo": "...", "notificationMedium": <SMS/WhatsApp/Email>}`
   (fields per kotlinx-serialization: `contactNo`, `notificationMedium`).
3. `POST …/user/verify-otp` (login path: `VerifyOTPRequest`) — body fields:
   `email, contact_no, is_mobile_login, country_code, grant_type, client_id, code_verifier`
   — standard OAuth/PKCE verification, returns the session grant.
   (`user/generate-otp` is the logged-in variant used by contact-change flows —
   `generateOtpWithToken`.)
4. Every request to cerberus carries `X-Firebase-AppCheck: <token>` when the
   token could be minted — the `AppCheckRequestPlugin(allowedHosts, tokenProvider,
   timeoutMillis=10000)` Ktor plugin adds it only for hosts in `allowedHosts`
   (configured with the host of `NetworkConfig("https://cerberus.ather.io/")`,
   i.e. `cerberus.ather.io`).

## Header notes

- The official app assembles `Source: ATHER_APP/<version>` at runtime from its
  own versionName (13.6.0 now); `X-Platform`, `X-Platform-Version`,
  `X-Device-Info`, `User-Agent` unchanged from 13.2.0.
- The version gate was NOT the fix for login — the 410 is endpoint-level. The
  version constant (`AtherApiClient.OFFICIAL_APP_VERSION = "13.6.0"`) still
  matters for the *other* cerberus APIs.

## What AtherMate does now

- Fresh OTP login cannot work. The auth screen explains the wall and offers
  **session-token sign-in**: paste a JWT captured from your own logged-in
  device (your own account/data); AtherMate auto-discovers the vehicle and
  completes the session (`AuthViewModel.signInWithToken`).
- `ather_session.json` adb seed import still works (see `SecureSessionStore`).
- Existing sessions are unaffected — data APIs do not enforce App Check.

## If Ather relaxes this

Everything needed to re-implement native login is above: PKCE pair generation
(S256), the new bodies, and the endpoint paths. The remaining unknown is
whether the `client_id` / `grant_type` values in `VerifyOTPRequest` are stable
strings — search the 13.6.0 dex for `client_id` neighbors to extract them.
