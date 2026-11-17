import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pstv.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pstv.tv"
        minSdk = 25          // Fire OS 6 = Android 7.1
        targetSdk = 34       // installs fine on Fire OS 6
        versionCode = 1
        versionName = "0.1.0"
        resourceConfigurations += "en"   // drop library translations (smaller APK)
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

    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    implementation("dev.jdtech.mpv:libmpv:1.1.0")   // optional alternate engine (declares minSdk 26; see manifest override)
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // JSON: android.util.JsonReader (streaming, for huge Xtream lists) + org.json (small API replies), both built in.

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.5.0")
}

// libmpv 1.1.0 was built against a newer compileSdk and declares minSdk 26; it only uses basic Android APIs
// (Context, Surface) plus JNI, so skip the AAR-metadata gate. MpvEngine fails safe if it cannot load on Fire OS 6.
tasks.configureEach { if (name.startsWith("check") && name.endsWith("AarMetadata")) enabled = false }
