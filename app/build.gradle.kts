plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tim.reader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tim.reader"
        minSdk = 33
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0-phase1"
    }

    buildTypes {
        getByName("release") {
            // Compose is much slower in debug builds, so shelf results only count from this variant.
            // Signed with the debug key for testing, same as the other Quartz apps.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Readium's libraries are built with this on, so the app has to match.
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes.add("META-INF/*")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // Same versions as Readium 3.4.0's version catalog.
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.9.0")
    implementation("androidx.compose.ui:ui:1.12.0")
    implementation("androidx.compose.foundation:foundation:1.12.0")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // EPUB
    implementation("org.readium.kotlin-toolkit:readium-shared:3.4.0")
    implementation("org.readium.kotlin-toolkit:readium-streamer:3.4.0")
    implementation("org.readium.kotlin-toolkit:readium-navigator:3.4.0")

    // CBZ, CBR and CB7
    implementation("me.zhanghai.android.libarchive:library:1.1.6")

    // JPEG 2000 pages, which Android can't decode by itself
    implementation("io.github.michaldvorak-gemalto:jp2-android:1.1.0")
}
