plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.skychat.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.skychat.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 22
        versionName = "0.22.0"
    }
    // Signing comes from environment variables (set by the GitHub workflow); without them the release APK is unsigned.
    val keystore: String? = System.getenv("SKY_KEYSTORE_FILE")
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("SKY_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("SKY_KEY_ALIAS")
            keyPassword = System.getenv("SKY_KEY_PASSWORD")
        }
    }
    buildTypes {
        // ws:// (no encryption) is allowed in the debug build only; the release build refuses all cleartext traffic.
        debug { manifestPlaceholders["cleartext"] = "true" }
        release {
            manifestPlaceholders["cleartext"] = "false"
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true      // libsignal uses java.time (API 26+)
    }
    kotlinOptions {
        jvmTarget = "17"
        // libsignal 0.94 is built with Kotlin 2.2; this lets the 2.0 compiler read it.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
    packaging {
        resources { excludes += setOf("libsignal_jni*.dylib", "signal_jni*.dll") }
    }
    buildFeatures { compose = true }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("io.github.webrtc-sdk:android:125.6422.07")
    implementation("org.signal:libsignal-android:0.94.4")
    implementation("org.signal:libsignal-client:0.94.4")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")   // QR code scanner (brings zxing core for drawing codes)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")   // encrypted local database
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
