package io.ather.pro.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.ather.pro.domain.model.GpsData
import io.ather.pro.ui.maps.StreetMapView
import androidx.compose.runtime.rememberUpdatedState
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay

@SuppressLint("SetJavaScriptEnabled", "MissingPermission")
@Composable
fun MapSection(
    gps: GpsData?,
    modifier: Modifier = Modifier,
    gpsUpdatedAt: Long? = null
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapPreferences = remember { context.getSharedPreferences("ather_map_preferences", Context.MODE_PRIVATE) }
    val mapThemes = listOf("night" to "Night", "day" to "Day", "neon" to "Neon")
    var mapTheme by remember {
        mutableStateOf(mapPreferences.getString("theme", "night")
            ?.takeIf { name -> mapThemes.any { it.first == name } } ?: "night")
    }
    var mapMode by remember {
        mutableStateOf(mapPreferences.getString("mode", "heading")
            ?.takeIf { it == "heading" || it == "north" } ?: "heading")
    }

    val atherLat = gps?.latitude?.takeIf { it.isFinite() && it in -90.0..90.0 }
    val atherLng = gps?.longitude?.takeIf { it.isFinite() && it in -180.0..180.0 }
    val atherAcc = gps?.accuracyMeters?.takeIf { it.isFinite() && it >= 0.0 }
    val atherAlt = gps?.altitudeMeters?.takeIf(Double::isFinite)
    val scooterReference by rememberUpdatedState(gps)
    val hasScooterFix = atherLat != null && atherLng != null

    // Phone / User live GPS and Compass states
    var phoneLat by remember { mutableStateOf<Double?>(null) }
    var phoneLng by remember { mutableStateOf<Double?>(null) }
    var phoneAcc by remember { mutableStateOf<Float?>(null) }
    var hasLocationPermission by remember {
        mutableStateOf(checkLocationPermission(context))
    }
    var locationServicesOn by remember {
        mutableStateOf(checkLocationServicesEnabled(context))
    }
    var azimuthDegrees by remember { mutableFloatStateOf(0f) }
    var unwrappedAzimuth by remember { mutableFloatStateOf(0f) }
    var hasInitAzimuth by remember { mutableStateOf(false) }
    var compassAccuracy by remember { mutableIntStateOf(SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) }
    // Manual-only recalibrate (token 0 = never auto-lock on first composition).
    var isRecalibrating by remember { mutableStateOf(false) }
    var recalibrateToken by remember { mutableIntStateOf(0) }

    val calibAccum = remember {
        object {
            var sinSum = 0.0
            var cosSum = 0.0
            var count = 0
            var token = -1
            fun reset(t: Int) {
                sinSum = 0.0
                cosSum = 0.0
                count = 0
                token = t
            }
            fun ensureToken(t: Int) {
                if (token != t) reset(t)
            }
            fun meanDegreesOrNull(): Float? {
                if (count < 1) return null
                val mean = Math.toDegrees(atan2(sinSum, cosSum)).toFloat()
                return (mean + 360f) % 360f
            }
        }
    }

    // Compass / rotation sensor listener corrected for display rotation and true north.
    DisposableEffect(lifecycleOwner) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        @Suppress("DEPRECATION")
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        // Prefer the fused rotation vector; fall back to accelerometer + magnetometer fusion.
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        val accelSensor = if (rotationSensor == null) sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
        val magnetSensor = if (rotationSensor == null) sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) else null

        val compassListener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val displayRotationMatrix = FloatArray(9)
            private val orientationValues = FloatArray(3)
            private val gravity = FloatArray(3)
            private val geomagnetic = FloatArray(3)
            private var hasGravity = false
            private var hasGeomagnetic = false
            private var lastHeadingTimestampNs = 0L

            private fun trueNorthAzimuth(matrix: FloatArray): Float? {
                @Suppress("DEPRECATION")
                val displayRotation = windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
                val (axisX, axisY) = when (displayRotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }

                if (!SensorManager.remapCoordinateSystem(matrix, axisX, axisY, displayRotationMatrix)) {
                    return null
                }
                SensorManager.getOrientation(displayRotationMatrix, orientationValues)
                val magneticAzimuth = Math.toDegrees(orientationValues[0].toDouble()).toFloat()

                // Android's orientation is magnetic-north referenced. Correct it so the map,
                // marker, and north pip all use the same true-north reference.
                val referenceLat = phoneLat ?: scooterReference?.latitude ?: 0.0
                val referenceLng = phoneLng ?: scooterReference?.longitude ?: 0.0
                val declination = GeomagneticField(
                    referenceLat.toFloat(),
                    referenceLng.toFloat(),
                    (atherAlt ?: 0.0).toFloat(),
                    System.currentTimeMillis()
                ).declination
                return (magneticAzimuth + declination + 360f) % 360f
            }

            private fun applyHeading(rawDeg: Float, timestampNs: Long, deadbandDeg: Float) {
                if (!hasInitAzimuth) {
                    unwrappedAzimuth = rawDeg
                    hasInitAzimuth = true
                    lastHeadingTimestampNs = timestampNs
                } else {
                    val normCurrent = (unwrappedAzimuth % 360f + 360f) % 360f
                    val diff = (rawDeg - normCurrent + 540f) % 360f - 180f
                    val enoughTimeElapsed = timestampNs - lastHeadingTimestampNs >= 33_000_000L
                    if (kotlin.math.abs(diff) >= deadbandDeg && enoughTimeElapsed) {
                        unwrappedAzimuth += diff
                        lastHeadingTimestampNs = timestampNs
                    }
                }
                azimuthDegrees = (unwrappedAzimuth % 360f + 360f) % 360f
            }

            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                var rawDeg: Float? = null

                if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR ||
                    event.sensor.type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    rawDeg = trueNorthAzimuth(rotationMatrix)
                } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    gravity[0] = 0.8f * gravity[0] + 0.2f * event.values[0]
                    gravity[1] = 0.8f * gravity[1] + 0.2f * event.values[1]
                    gravity[2] = 0.8f * gravity[2] + 0.2f * event.values[2]
                    hasGravity = true
                    if (hasGeomagnetic) {
                        val r = FloatArray(9)
                        val i = FloatArray(9)
                        if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                            rawDeg = trueNorthAzimuth(r)
                        }
                    }
                } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                    geomagnetic[0] = 0.8f * geomagnetic[0] + 0.2f * event.values[0]
                    geomagnetic[1] = 0.8f * geomagnetic[1] + 0.2f * event.values[1]
                    geomagnetic[2] = 0.8f * geomagnetic[2] + 0.2f * event.values[2]
                    hasGeomagnetic = true
                    if (hasGravity) {
                        val r = FloatArray(9)
                        val i = FloatArray(9)
                        if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                            rawDeg = trueNorthAzimuth(r)
                        }
                    }
                }

                if (rawDeg != null) {
                    // Recalibrate: keep a running circular mean every sample (not after N).
                    if (isRecalibrating) {
                        calibAccum.ensureToken(recalibrateToken)
                        val rad = Math.toRadians(rawDeg.toDouble())
                        calibAccum.sinSum += sin(rad)
                        calibAccum.cosSum += cos(rad)
                        calibAccum.count += 1
                        calibAccum.meanDegreesOrNull()?.let { locked ->
                            unwrappedAzimuth = locked
                            azimuthDegrees = locked
                            hasInitAzimuth = true
                            lastHeadingTimestampNs = event.timestamp
                        }
                        return
                    }

                    // Only freeze on truly unreliable readings (LOW is common indoors).
                    if (compassAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                        return
                    }

                    applyHeading(rawDeg, event.timestamp, deadbandDeg = 1.25f)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (sensor == null) return
                val type = sensor.type
                if (type == Sensor.TYPE_MAGNETIC_FIELD ||
                    type == Sensor.TYPE_ROTATION_VECTOR ||
                    type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
                ) {
                    compassAccuracy = accuracy
                }
            }
        }

        fun startCompass() {
            sensorManager?.unregisterListener(compassListener)
            if (rotationSensor != null && sensorManager != null) {
                sensorManager.registerListener(compassListener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
            } else if (sensorManager != null) {
                if (accelSensor != null) sensorManager.registerListener(compassListener, accelSensor, SensorManager.SENSOR_DELAY_UI)
                if (magnetSensor != null) sensorManager.registerListener(compassListener, magnetSensor, SensorManager.SENSOR_DELAY_UI)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) startCompass()
            if (event == Lifecycle.Event.ON_PAUSE) sensorManager?.unregisterListener(compassListener)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startCompass()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sensorManager?.unregisterListener(compassListener)
        }
    }

    // Phone GPS: watch permission + location toggle; subscribe the moment location is on.

    DisposableEffect(lifecycleOwner) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        var subscribed = false

        fun clearPhoneFix() {
            phoneLat = null
            phoneLng = null
            phoneAcc = null

        }

        fun applyFix(location: Location) {
            if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
            val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
            if (age !in 0L..120_000L) return
            phoneLat = location.latitude
            phoneLng = location.longitude
            phoneAcc = location.accuracy
        }

        lateinit var refreshSubscription: () -> Unit

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) = applyFix(location)
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {
                locationServicesOn = true
                refreshSubscription()
            }
            override fun onProviderDisabled(provider: String) {
                locationServicesOn = checkLocationServicesEnabled(context)
                if (!locationServicesOn) {
                    if (subscribed && locationManager != null) {
                        runCatching { locationManager.removeUpdates(this) }
                        subscribed = false
                    }
                    clearPhoneFix()
                }
            }
        }

        fun unsubscribe() {
            if (!subscribed || locationManager == null) return
            runCatching { locationManager.removeUpdates(locationListener) }
            subscribed = false
        }

        @SuppressLint("MissingPermission")
        refreshSubscription = {
            hasLocationPermission = checkLocationPermission(context)
            locationServicesOn = checkLocationServicesEnabled(context)
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                !hasLocationPermission || !locationServicesOn || locationManager == null) {
                unsubscribe()
                if (!locationServicesOn || !hasLocationPermission) clearPhoneFix()
            } else {
                unsubscribe()
                try {
                    val providers = buildList {
                        add(LocationManager.GPS_PROVIDER)
                        add(LocationManager.NETWORK_PROVIDER)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            add(LocationManager.FUSED_PROVIDER)
                        }
                    }
                    var best: Location? = null
                    for (provider in providers) {
                        val enabled = runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
                        if (!enabled) continue
                        locationManager.requestLocationUpdates(provider, 1_000L, 1f, locationListener)
                        subscribed = true
                        val last = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                        val age = last?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L }
                        if (last != null && age != null && age in 0L..120_000L &&
                            (best == null || last.accuracy < best.accuracy)) {
                            best = last
                        }
                    }
                    best?.let(::applyFix)
                } catch (_: SecurityException) {
                    hasLocationPermission = false
                    unsubscribe()
                    clearPhoneFix()
                }
            }
        }

        val providerReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                refreshSubscription()
            }
        }
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION).apply {
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            context,
            providerReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshSubscription()
            }
            if (event == Lifecycle.Event.ON_PAUSE) unsubscribe()
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        refreshSubscription()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            runCatching { context.unregisterReceiver(providerReceiver) }
            unsubscribe()
        }
    }

    // Distance calculation between Phone and Ather (Haversine formula)
    val distanceMeters: Double? = remember(phoneLat, phoneLng, atherLat, atherLng) {
        if (phoneLat != null && phoneLng != null && atherLat != null && atherLng != null) {
            val r = 6371000.0 // Earth radius in meters
            val dLat = Math.toRadians(atherLat - phoneLat!!)
            val dLng = Math.toRadians(atherLng - phoneLng!!)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(phoneLat!!)) * cos(Math.toRadians(atherLat)) *
                    sin(dLng / 2) * sin(dLng / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            r * c
        } else null
    }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(mapTheme, pageLoaded, webViewInstance) {
        if (!pageLoaded) return@LaunchedEffect
        webViewInstance?.evaluateJavascript(
            "window.setMapTheme('$mapTheme');", null
        )
    }
    LaunchedEffect(mapMode, pageLoaded, webViewInstance) {
        if (!pageLoaded) return@LaunchedEffect
        webViewInstance?.evaluateJavascript(
            "window.setMapMode('$mapMode');", null
        )
    }

    // Manual recalibrate only — auto-run on open was locking a bad heading and putting N wrong.
    LaunchedEffect(recalibrateToken) {
        if (recalibrateToken == 0) return@LaunchedEffect
        calibAccum.reset(recalibrateToken)
        isRecalibrating = true
        webViewInstance?.evaluateJavascript(
            "if (window.setHeadingFrozen) { window.setHeadingFrozen(true); }",
            null
        )
        delay(1_500L)
        val locked = calibAccum.meanDegreesOrNull()
        if (locked != null && calibAccum.count >= 5) {
            unwrappedAzimuth = locked
            azimuthDegrees = locked
            hasInitAzimuth = true
        }
        isRecalibrating = false
        webViewInstance?.evaluateJavascript(
            "if (window.setHeadingFrozen) { window.setHeadingFrozen(false); }",
            null
        )
        if (pageLoaded && webViewInstance != null) {
            val jsHeading = String.format(
                Locale.US,
                "if (window.setMapHeading) { window.setMapHeading(%.2f); }",
                azimuthDegrees
            )
            webViewInstance?.evaluateJavascript(jsHeading, null)
        }
    }

    LaunchedEffect(isRecalibrating, pageLoaded, webViewInstance) {
        if (!pageLoaded || webViewInstance == null) return@LaunchedEffect
        val frozen = if (isRecalibrating) "true" else "false"
        webViewInstance?.evaluateJavascript(
            "if (window.setHeadingFrozen) { window.setHeadingFrozen($frozen); }",
            null
        )
    }

    // Heading updates are isolated from GPS marker updates. Sensor events can arrive at
    // 30 Hz; re-sending every marker on each event made active map gestures stutter.
    LaunchedEffect(azimuthDegrees, pageLoaded, isRecalibrating) {
        if (pageLoaded && webViewInstance != null && !isRecalibrating) {
            val jsHeading = String.format(
                Locale.US,
                "if (window.setMapHeading) { window.setMapHeading(%.2f); }",
                azimuthDegrees
            )
            webViewInstance?.evaluateJavascript(jsHeading, null)
        }
    }

    LaunchedEffect(atherLat, atherLng, atherAcc, phoneLat, phoneLng, phoneAcc, pageLoaded) {
        val view = webViewInstance ?: return@LaunchedEffect
        if (!pageLoaded) return@LaunchedEffect

        if (atherLat != null && atherLng != null) {
            val jsAther = String.format(
                Locale.US,
                "if (window.updateAtherMarker) { window.updateAtherMarker(%.6f, %.6f, %.1f); }",
                atherLat,
                atherLng,
                atherAcc ?: 0.0
            )
            view.evaluateJavascript(jsAther, null)
        } else {
            view.evaluateJavascript(
                "if (window.removeAtherMarker) { window.removeAtherMarker(); }",
                null
            )
        }

        if (phoneLat != null && phoneLng != null) {
            val jsPhone = String.format(
                Locale.US,
                "if (window.updatePhoneMarker) { window.updatePhoneMarker(%.6f, %.6f, %.1f, %.1f); }",
                phoneLat!!,
                phoneLng!!,
                phoneAcc ?: 0.0f,
                azimuthDegrees
            )
            view.evaluateJavascript(jsPhone, null)

        } else {
            view.evaluateJavascript(
                "if (window.removePhoneMarker) { window.removePhoneMarker(); }",
                null
            )
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Top Bar: Navigation Header + Mode Switch (Tactical Map vs Radar HUD)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = colorScheme.secondary
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "SCOOTER LOCATION",
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Surface(
                    color = colorScheme.secondary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (mapMode == "heading") "HEADING UP" else "NORTH UP",
                        color = colorScheme.secondary,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                mapThemes.forEach { (id, label) ->
                    FilterChip(selected = mapTheme == id, onClick = {
                        mapTheme = id
                        mapPreferences.edit().putString("theme", id).apply()
                    }, label = { Text(label) })
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("heading" to "Heading up", "north" to "North up").forEach { (id, label) ->
                    FilterChip(selected = mapMode == id, onClick = {
                        mapMode = id
                        mapPreferences.edit().putString("mode", id).apply()
                    }, label = { Text(label) })
                }
                androidx.compose.material3.TextButton(onClick = {
                    webViewInstance?.evaluateJavascript("window.followScooter();", null)
                }, enabled = hasScooterFix) { Text("Scooter") }
                androidx.compose.material3.TextButton(onClick = {
                    webViewInstance?.evaluateJavascript("window.fitBoth();", null)
                }) { Text("Show both") }
            }

            // 1. Full-Width Edge-to-Edge Circular Minimap (Utilizes all available width without wasted space)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val mapHeight = (maxWidth * 1.25f).coerceIn(340.dp, 520.dp)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(mapHeight)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF0B0D0F)),
                    contentAlignment = Alignment.Center
                ) {
                    // Layer 1: Live Leaflet Street Map (1:1 Touch Geometry - perfectly responsive dragging & panning)
                    StreetMapView(modifier = Modifier.matchParentSize()) { view ->
                        webViewInstance = view
                        pageLoaded = view != null
                    }

                }

                // Floating Zoom and Fit Controls Overlay (Right side of Map)
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = colorScheme.surface.copy(alpha = 0.90f),
                        shadowElevation = 4.dp
                    ) {
                        IconButton(
                            onClick = {
                                webViewInstance?.evaluateJavascript("if (window.zoomIn) { window.zoomIn(); }", null)
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Zoom In",
                                modifier = Modifier.size(18.dp),
                                tint = colorScheme.onSurface
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = colorScheme.surface.copy(alpha = 0.90f),
                        shadowElevation = 4.dp
                    ) {
                        IconButton(
                            onClick = {
                                webViewInstance?.evaluateJavascript("if (window.zoomOut) { window.zoomOut(); }", null)
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "Zoom Out",
                                modifier = Modifier.size(18.dp),
                                tint = colorScheme.onSurface
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = colorScheme.surface.copy(alpha = 0.90f),
                        shadowElevation = 4.dp
                    ) {
                        IconButton(
                            onClick = {
                                webViewInstance?.evaluateJavascript("if (window.followMe) { window.followMe(); }", null)
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CenterFocusStrong,
                                contentDescription = "Follow my location",
                                modifier = Modifier.size(18.dp),
                                tint = colorScheme.secondary
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = colorScheme.surface.copy(alpha = 0.90f),
                        shadowElevation = 4.dp
                    ) {
                        IconButton(
                            onClick = { recalibrateToken += 1 },
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = "Recalibrate compass. Hold phone still for a few seconds"
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Explore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (isRecalibrating) {
                                    colorScheme.secondary
                                } else {
                                    colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }

            Text(
                text = "© OpenStreetMap contributors",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp).clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.openstreetmap.org/copyright"))) }
                }
            )

            if (isRecalibrating) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Hold still — calibrating compass…",
                    color = colorScheme.secondary,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (!hasLocationPermission || !locationServicesOn) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        !hasLocationPermission -> "Location permission is off — allow location for this app"
                        else -> "Location is not turned on"
                    },
                    color = colorScheme.error,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = if (!hasLocationPermission) {
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                )
                            } else {
                                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                            }
                            runCatching { context.startActivity(intent) }
                        }
                        .semantics {
                            contentDescription = "Open location settings"
                        }
                )
            }

            Spacer(Modifier.height(12.dp))

            // Dual GPS Metrics & Live Accuracy Indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Scooter GPS Dot status
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E676))
                    )
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "SCOOTER",
                            color = colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = atherAcc?.let {
                                val accuracy = if (it < 1.0) "${(it * 100).toInt()} cm" else "${String.format(Locale.US, "%.1f", it)} m"
                                "Accuracy: ±$accuracy"
                            } ?: if (hasScooterFix) "Accuracy unknown" else "Waiting for scooter GPS",
                            color = colorScheme.onSurface,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        )
                    }
                }

                // Right: Phone GPS Arrow status
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Navigation,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = Color(0xFF00B0FF)
                    )
                    Spacer(Modifier.width(6.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "YOUR PHONE",
                            color = colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold)
                        )
                        val phoneStatus = when {
                            !hasLocationPermission -> "Permission off"
                            !locationServicesOn -> "Location off"
                            phoneLat != null && phoneAcc != null -> {
                                val a = phoneAcc!!
                                val accText = if (a < 1.0f) {
                                    "${(a * 100).toInt()} cm"
                                } else {
                                    String.format(Locale.US, "%.1f", a) + " m"
                                }
                                "Accuracy: ±$accText"
                            }
                            else -> "Acquiring fix…"
                        }
                        Text(
                            text = phoneStatus,
                            color = if (!hasLocationPermission || !locationServicesOn) {
                                colorScheme.error
                            } else {
                                colorScheme.onSurface
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        )
                    }
                }
            }

            gpsUpdatedAt?.let { timestamp ->
                Text("Scooter location received " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp)),
                    modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant)
            }

            // Live distance guidance banner
            if (distanceMeters != null) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Straight-line Distance:",
                            color = colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                        )
                        val distFormatted = if (distanceMeters < 1000) {
                            "${distanceMeters.toInt()} meters away"
                        } else {
                            "${String.format(Locale.US, "%.2f", distanceMeters / 1000.0)} km away"
                        }
                        Text(
                            text = distFormatted,
                            color = colorScheme.secondary,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // One-Tap Find My Scooter — Maps package, geo intent, then web fallback
            Button(
                onClick = {
                    if (atherLat == null || atherLng == null) {
                        Toast.makeText(context, "Scooter GPS location is not available yet", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val opened = openFindMyNavigation(context, atherLat, atherLng)
                    if (!opened) {
                        Toast.makeText(context, "Could not open navigation to scooter", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (hasScooterFix) {
                            "Find My Scooter. Open walking navigation to scooter location"
                        } else {
                            "Find My Scooter unavailable. Waiting for scooter GPS fix"
                        }
                    },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorScheme.primary,
                    contentColor = colorScheme.onPrimary
                ),
                enabled = hasScooterFix,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.NearMe,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (hasScooterFix) "Navigate to Scooter" else "Waiting for GPS fix",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onPrimary
                    )
                )
            }
        }
    }
}

/** Try Google Maps walking nav, then generic geo intent, then web directions. */
private fun openFindMyNavigation(context: Context, lat: Double, lng: Double): Boolean {
    val gmmIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("google.navigation:q=$lat,$lng&mode=w")
    ).apply {
        setPackage("com.google.android.apps.maps")
    }
    val geoIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("geo:$lat,$lng?q=$lat,$lng(Scooter)")
    )
    val webIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng&travelmode=walking")
    )
    return listOf(gmmIntent, geoIntent, webIntent).any { intent ->
        try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}

private fun checkLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
}

private fun checkLocationServicesEnabled(context: Context): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ||
        runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) }.getOrDefault(false))
}
