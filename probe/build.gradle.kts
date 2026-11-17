plugins {
    id("com.android.application")
}

// Personal experiment: takes the YouTube Music package name so the assistant hands its
// YouTube Music requests to this app, which logs them and forwards them to YMusic V4.
// Never publish or share this build.
android {
    namespace = "com.voicebridge.probe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.google.android.apps.youtube.music"
        minSdk = 26
        targetSdk = 35
        // High, so a "please update the app" version check never trips.
        versionCode = 2_000_000_000
        versionName = "99.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
