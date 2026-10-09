plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// A release's version is its tag's. The Release workflow passes a tag v1.2.3 as TERN_VERSION (a
// build may give -PternVersion instead): versionName 1.2.3, and versionCode 10203, major * 10000 +
// minor * 100 + patch, so that each release's code is greater than the one before. A build given
// neither, on a laptop or on main, is 0.1.0, code 1.
val ternVersion: Pair<String, Int>? =
    ((findProperty("ternVersion") as String?) ?: System.getenv("TERN_VERSION"))?.trim()?.takeIf { it.isNotEmpty() }?.let { given ->
        val m = Regex("v?(\\d+)\\.(\\d+)\\.(\\d+)").matchEntire(given)
            ?: throw GradleException("The version \"$given\" is not MAJOR.MINOR.PATCH, as a tag v1.2.3 gives")
        val (major, minor, patch) = m.destructured.toList().map(String::toInt)
        if (minor > 99 || patch > 99) throw GradleException("The version \"$given\" has a minor or patch over 99, which its versionCode cannot hold")
        "$major.$minor.$patch" to major * 10000 + minor * 100 + patch
    }

android {
    namespace = "org.ternmesh.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.ternmesh.app"
        // Android 8: notification channels, and a Bluetooth stack that asks for an MTU reliably.
        minSdk = 26
        targetSdk = 35
        versionCode = ternVersion?.second ?: 1
        versionName = ternVersion?.first ?: "0.1.0"
    }

    // Two keys. The debug key is kept in the repository, so that every debug build, from CI or a
    // laptop, installs over the one before it; anyone can sign with it, so it signs only the debug
    // app, which has a package of its own (org.ternmesh.app.debug) and is never the one ternmesh.org
    // names. The release key is the maintainers' alone: CI is given it as secrets on main, and a
    // build without them leaves the release APK unsigned.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        System.getenv("TERN_RELEASE_KEYSTORE")?.let { keystore ->
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("TERN_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("TERN_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("TERN_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
    }
}

dependencies {
    implementation(project(":protocol"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // QR codes: ZXing's encoder draws a node's link, and its embedded scanner reads one with the
    // camera. Neither needs Google Play services, which not every phone that runs a node has.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }

    // The map: MapLibre draws OpenFreeMap's tiles, which need no key and no Google Play services.
    implementation("org.maplibre.gl:android-sdk:11.13.5")
}
