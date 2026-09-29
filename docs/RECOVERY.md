# Architecture & Recovery Record

## Provenance
- Restored repository: `/home/kar/funProjects/AtherPro`
- Application ID: `io.ather.pro`
- Target SDK: 34 (Android 14)
- Minimum SDK: 26 (Android 8.0)

## Components
- Kotlin Jetpack Compose UI with Material You design
- Room SQLite local database with offline caching
- EncryptedSharedPreferences (AES-256) for secure local token and session storage
- OkHttp WebSocket and REST client for real-time telemetry and control
- Foreground Service and WorkManager for background battery and charging monitoring
- App Widgets with dynamic remote views and theme support
