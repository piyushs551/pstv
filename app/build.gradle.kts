import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// API keys are baked in at build time from keys.properties (git-ignored) or PSTV_* environment variables.
// Create the file with scripts\set-keys.ps1. Nothing secret is ever committed.
val keyProps = Properties().also { p ->
    rootProject.file("keys.properties").takeIf { it.exists() }?.inputStream()?.use { p.load(it) }
}
fun keyOf(name: String): String =
    (keyProps.getProperty(name) ?: System.getenv("PSTV_" + name.uppercase()) ?: "").trim().replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.pstv.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pstv.tv"
        minSdk = 25          // Fire OS 6 = Android 7.1
        targetSdk = 34       // installs fine on Fire OS 6
        versionCode = 4
        versionName = "0.1.0"
        resourceConfigurations += "en"   // drop library translations (smaller APK)
        buildConfigField("String", "TMDB_KEY", "\"${keyOf("tmdb")}\"")
        buildConfigField("String", "SOURCE", "\"${keyOf("source")}\"")
        buildConfigField("String", "M3U_URL", "\"${keyOf("m3u_url")}\"")
        buildConfigField("String", "X_HOST", "\"${keyOf("x_host")}\"")
        buildConfigField("String", "X_USER", "\"${keyOf("x_user")}\"")
        buildConfigField("String", "X_PASS", "\"${keyOf("x_pass")}\"")
        buildConfigField("String", "OS_KEY", "\"${keyOf("os_key")}\"")
        buildConfigField("String", "OS_USER", "\"${keyOf("os_user")}\"")
        buildConfigField("String", "OS_PASS", "\"${keyOf("os_pass")}\"")
        ndk { abiFilters += (project.findProperty("pstv.abi") as String? ?: "armeabi-v7a").split(",") }
    }

    buildTypes {
        // Debug is minified/shrunk too so the APK you test is the size you ship.
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }

    packaging {
        jniLibs.useLegacyPackaging = true   // compress native libs inside the APK (extracted on install)
        resources.excludes += setOf("META-INF/*.version", "META-INF/LICENSE*", "META-INF/NOTICE*", "DebugProbesKt.bin", "kotlin/**", "*.kotlin_builtins")
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val media3 = "1.7.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    // FFmpeg software audio/video decoders for ExoPlayer (MP2, DTS, etc. that the Fire TV hardware lacks). minSdk 21, small.
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.7.1-0.9.0")

    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // JSON: android.util.JsonReader (streaming, for huge Xtream lists) + org.json (small API replies), both built in.

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.5.0")
}

// Media3 1.7.1 and the FFmpeg extension were built against a newer compileSdk (minCompileSdk metadata only);
// skip the AAR-metadata gate. The extension itself supports minSdk 21.
tasks.configureEach { if (name.startsWith("check") && name.endsWith("AarMetadata")) enabled = false }
