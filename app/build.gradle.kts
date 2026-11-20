plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.steampp.wattkit"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.steampp.wattkit"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        // Release signing checks WATTKIT_* env vars first, then the local official
        // keystore so locally built APKs remain upgrade-compatible with v1.0.0.
        // In CI or external environments without either, release falls back to debug.
        val envStore = System.getenv("WATTKIT_STORE_FILE")
        val localOfficialStore = "C:/Users/Administrator/Documents/Watt-Toolkit-Keystore/repair-test.keystore"
        if (envStore != null && file(envStore).exists()) {
            create("release") {
                storeFile = file(envStore)
                storePassword = System.getenv("WATTKIT_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("WATTKIT_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("WATTKIT_KEY_PASSWORD") ?: ""
                enableV1Signing = true
                enableV2Signing = true
            }
        } else if (file(localOfficialStore).exists()) {
            create("release") {
                storeFile = file(localOfficialStore)
                storePassword = "android"
                keyAlias = "androidrepair"
                keyPassword = "android"
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // R8 is intentionally left off for now; enable together with
            // proguard-rules.pro once rules are verified on a real build.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ML Kit Barcode Scanning for Steam Guard QR Code
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Unit tests for the pure-JVM SNI parser
    testImplementation("junit:junit:4.13.2")
}
