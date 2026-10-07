import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "io.ather.pro"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.ather.pro"
        minSdk = 26
        targetSdk = 34
        versionCode = providers.gradleProperty("athrVersionCode").orNull?.toInt() ?: 20
        versionName = providers.gradleProperty("athrVersionName").orNull ?: "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Personal values stay out of the repo: set in local.properties (gitignored).
        val local = Properties().apply {
            val file = rootProject.file("local.properties")
            if (file.exists()) file.inputStream().use { load(it) }
        }
        buildConfigField("String", "SUPPORT_UPI_ID",
            "\"${local.getProperty("athermate.upi") ?: ""}\"")
        buildConfigField("String", "SUPPORT_UPI_NAME",
            "\"${local.getProperty("athermate.upiName") ?: ""}\"")
    }

    // A release must use the same private signing key as the existing public APK.
    val releaseKey = providers.environmentVariable("ATHR_SIGNING_STORE").orNull
    signingConfigs {
        create("publisher") {
            if (releaseKey != null) {
                storeFile = file(releaseKey)
                storePassword = providers.environmentVariable("ATHR_SIGNING_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("ATHR_SIGNING_ALIAS").orNull
                keyPassword = providers.environmentVariable("ATHR_SIGNING_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("publisher")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Ather WebSocket API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.40")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // QR pairing: CameraX frames decoded by the embedded ZXing core
    val cameraVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraVersion")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")
    implementation("com.google.zxing:core:3.5.3")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
}

// Native computation is packaged for physical phones and emulators.
val nativeOutput = layout.buildDirectory.dir("generated/rustJniLibs")
// On Windows a bare "bash" can resolve to WSL's, which cannot run Windows paths.
val bashExecutable: String = if (System.getProperty("os.name").contains("windows", ignoreCase = true)) {
    System.getenv("ATHER_BASH")
        ?: listOf("C:\\Program Files\\Git\\bin\\bash.exe", "C:\\Program Files (x86)\\Git\\bin\\bash.exe")
            .firstOrNull { File(it).isFile }
        ?: "bash"
} else {
    "bash"
}
val buildRust by tasks.registering(Exec::class) {
    inputs.files(fileTree("../../rust/ather-math") { exclude("target/**") })
    inputs.file("../../scripts/build-android-rust.sh")
    outputs.dir(nativeOutput)
    doFirst { delete(nativeOutput.get().asFile) }
    environment("ANDROID_HOME", android.sdkDirectory.absolutePath)
    commandLine(bashExecutable, file("../../scripts/build-android-rust.sh").absolutePath,
        nativeOutput.get().asFile.absolutePath)
}
android.sourceSets.getByName("main").jniLibs.srcDir(nativeOutput)
tasks.named("preBuild").configure { dependsOn(buildRust) }

// Fail before producing an unsigned or differently keyed release by accident.
tasks.matching { it.name == "validateSigningRelease" }.configureEach {
    doFirst {
        require(!System.getenv("ATHR_SIGNING_STORE").isNullOrBlank() &&
            !System.getenv("ATHR_SIGNING_ALIAS").isNullOrBlank() &&
            !System.getenv("ATHR_SIGNING_STORE_PASSWORD").isNullOrBlank() &&
            !System.getenv("ATHR_SIGNING_KEY_PASSWORD").isNullOrBlank()) {
            "Configure the original release signing key first; see docs/APP-UPDATES.md."
        }
    }
}
