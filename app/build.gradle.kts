plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sdkVer = (project.findProperty("desktop.sdk") as String).toInt()

android {
    namespace = "com.android.desktoplauncher"
    compileSdk = sdkVer

    defaultConfig {
        // Same package as the stock Trebuchet/Quickstep: the ROM (config_recentsComponentName) and SystemUI look for exactly this name.
        applicationId = "com.android.launcher3"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // One fixed signing key for every build (kept in the repository), so each new APK installs over the old one
    // without uninstalling it first. Without this GitHub creates a new random debug key for every build.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Android 7-8 lack some newer Java classes (java.time, Optional, …)
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { aidl = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    // hidden framework classes, compile-time stubs only (the real ones are in the system)
    compileOnly(files("libs/hidden-stubs.jar"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
