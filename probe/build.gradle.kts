plugins {
    id("com.android.application")
}

// Personal experiment: takes another music app's package name (YouTube Music or Spotify) so the
// assistant hands that app's requests to this one, which logs them and forwards them to YMusic V4.
// Never publish or share these builds.
android {
    namespace = "com.voicebridge.probe"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        // High, so a "please update the app" version check never trips.
        versionCode = 2_000_000_000
        versionName = "99.0.0"
    }

    flavorDimensions += "target"
    productFlavors {
        create("ytmusic") {
            dimension = "target"
            applicationId = "com.google.android.apps.youtube.music"
            resValue("string", "app_name", "YouTube Music")
        }
        create("spotify") {
            dimension = "target"
            applicationId = "com.spotify.music"
            resValue("string", "app_name", "Spotify")
        }
    }

    buildFeatures {
        resValues = true
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
