import java.util.Properties

/*
 * Release signing: passwords live in keystore.properties next to settings.gradle.kts (NOT in
 * git). Keys: storeFile, storePassword, keyAlias, keyPassword. Without that file, release
 * builds are simply unsigned.
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.thorreader.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.thorreader.app"
        minSdk = 33
        targetSdk = 34
        versionCode = 3
        versionName = "0.2.1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: removes unused code and resources, and strips Log.v/d/i (see proguard-rules.pro).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Same key and same applicationId as release, so debug and release APKs install
            // over each other without uninstalling (the library is kept). The launcher label
            // differs: "Thor Reader (dev)" (src/debug/res/values/strings.xml).
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
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
    packaging {
        resources {
            excludes += listOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/versions/9/module-info.class",
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // Book database (used by the future bookshelf too)
    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    // MuPDF from Artifex (AGPL v3 - see PLAN.md "Licensing")
    implementation("com.artifex.mupdf:fitz:1.28.5")

    // Comic archives: CBR (RAR4), CBZ/CB7 (zip/7z)
    implementation("com.github.junrar:junrar:8.1.2")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")

    // Markdown -> HTML when MuPDF can't open .md itself (BSD 2-Clause)
    implementation("org.commonmark:commonmark:0.33.0")

    testImplementation("junit:junit:4.13.2")
    // Android has org.json built in; plain JVM unit tests need the real one.
    testImplementation("org.json:json:20240303")
}
