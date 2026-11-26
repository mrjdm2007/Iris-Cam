plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStorePath = System.getenv("IRIS_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("IRIS_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("IRIS_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("IRIS_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.example.videocam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.iris.cam"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        create("irisRelease") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            // Configure the four IRIS_RELEASE_* environment variables in CI to sign distributable builds.
            // Without them, use the debug key for internal testing only.
            signingConfig = signingConfigs.getByName(if (hasReleaseSigning) "irisRelease" else "debug")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}


dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
}
