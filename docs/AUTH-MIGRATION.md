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

## The new flow (official 13.6.0) — REAL endpoints

The login endpoints use **obfuscated paths** (why "generate-login-otp" never
appears in the dex):

- `POST https://cerberus.ather.io/api/v2/req/8h91` — GenerateLoginOTP
  (`coronalite/constants/a.java`, enum case GenerateLoginOTP)
- `POST https://cerberus.ather.io/api/v2/req/8h71` — VerifyLoginOTP
- (`auth/generate-otp`, `user/generate-otp`, `user/verify-otp` are the
  contact-change/verification endpoints, also App-Check enforced)

**GenerateLoginOTP body** (`LoginOTPRequest`, kotlinx-serialization fields):
`email, contact_no, country_code, notification_medium, code_challenge, code_challenge_method`
where `notification_medium` is a JSON object `{"whatsapp": bool, "sms": bool}`
(custom serializer `coronalite/helpers/f.java`).

**VerifyLoginOTP body** (`VerifyOTPRequest`):
`email, contact_no, is_mobile_login, country_code, grant_type, client_id, code_verifier`
— standard OAuth/PKCE verification.

## App Check enforcement — verified live

Error codes on the real login endpoint (`api/v2/req/8h91`):
- no `X-Firebase-AppCheck` header → `401 {"errorCode":1013}`
- garbage token → `401 {"errorCode":1014}`
- (a valid token presumably proceeds; the official Ktor plugin
  `AppCheckRequestPlugin(allowedHosts={cerberus.ather.io}, tokenProvider, 10s)`
  attaches it, and sends header-less when minting fails)

App Check tokens are minted via **Play Integrity** (`firebase.appcheck.playintegrity`)
and are bound to the official package + signing cert at Google's exchange
endpoint. A third-party app cannot mint one; a custom ROM without a passing
integrity verdict cannot either — **the official app itself cannot log in fresh
on such devices** (its own plugin sends header-less when minting fails, and the
server rejects with 1013).

## Token extraction (the working path for fresh sign-in)

The official app persists its session in **Jetpack DataStore**
(`com.ather.common.datastore.SessionManager`, `UserSession.accessToken` /
`refreshToken`) at:

```
/data/data/com.athermobileapp/files/datastore/<…>.preferences_pb
```

On a rooted/custom-ROM device:

```
adb shell su -c "cat /data/data/com.athermobileapp/files/datastore/*.preferences_pb" | strings | grep ^eyJ
```

The `eyJ…` string is the access JWT. Paste it into AtherMate's
**Sign-in trouble? Advanced options → Session token (JWT)** field — the vehicle
is auto-discovered and the session completes (same token the app would have
received from login).

AtherMate's own existing seed path (`ather_session.json` via adb push, see
`SecureSessionStore.importUntrackedSeedIfPresent`) also still works.

## Emulator finding (13.6.0 on a Google-APIs Android 14 emulator)

The official app **runs** (arm64 translation works on x86_64 emulators) but
blocks itself with its own device check on launch:

> "Something went wrong — Check that Google Play is enabled on your device and
> that you're using an up-to-date version before opening the app…"

The dialog re-appears after Close. So the official app cannot be used as a
token source on emulators either. Re-signing/patching the official APK to
remove its device checks is also futile for login: any modified APK changes
the signing cert, and App Check binds to the cert — the wall holds.

## Practical consequences

- Fresh OTP login requires: official app + Play-certified device + passing
  App Check. All three together only exist on stock devices.
- Custom-ROM users: make the official app run on YOUR device (root path:
  Magisk + PlayIntegrityFix + Shamiko, hide developer options), sign in
  there, then extract the token with root and paste into AtherMate — or use
  any stock Android device for the official login and extract there.
- Existing sessions (JWT) are unaffected by all of this.

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
