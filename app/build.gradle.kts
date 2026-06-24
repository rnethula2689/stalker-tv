import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildTime: String =
    SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'").apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

android {
    namespace = "com.stalkertv.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.stalkertv.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 42
        versionName = "0.42"
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    signingConfigs {
        create("stable") {
            storeFile = file("signing.keystore")
            storePassword = "stalkertv"
            keyAlias = "stalker"
            keyPassword = "stalkertv"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stable")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")
    // FFmpeg software decoders (AC-3, MP2, etc.) for ExoPlayer.
    // Maven Central; version is <media3version>-<nextlibversion> and must match the Media3 above.
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.7.1-0.9.0")
}
