import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.videocam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.iris.cam"
        minSdk = 29
        targetSdk = 34
        versionCode = providers.gradleProperty("VERSION_CODE").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("VERSION_NAME").orNull ?: "1.0"
    }

    signingConfigs {
        // A persistent upload key is supplied by GitHub Actions secrets for install-in-place updates.
        // Without it, CI can still build the debug APK for direct testing.
        val encodedKey = System.getenv("ANDROID_KEYSTORE_BASE64")
        if (!encodedKey.isNullOrBlank()) {
            create("release") {
                val keyFile = rootProject.file("iris-upload-key.jks")
                keyFile.writeBytes(Base64.getDecoder().decode(encodedKey))
                storeFile = keyFile
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!System.getenv("ANDROID_KEYSTORE_BASE64").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
}
