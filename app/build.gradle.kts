plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val stableDebugKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val stableDebugStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val stableDebugKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val stableDebugKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val hasStableDebugSigning = listOf(
    stableDebugKeystorePath,
    stableDebugStorePassword,
    stableDebugKeyAlias,
    stableDebugKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.stealth.vaultnotes"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stealth.vaultnotes"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasStableDebugSigning) {
            create("stableDebug") {
                storeFile = file(stableDebugKeystorePath!!)
                storePassword = stableDebugStorePassword
                keyAlias = stableDebugKeyAlias
                keyPassword = stableDebugKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasStableDebugSigning) {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
}
