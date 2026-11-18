plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The same persistent keystore signs both debug (for continuity with existing
// test installs) and release APKs. Release builds intentionally fail if secrets
// or the keystore are missing; they must never fall back to an unsigned APK.
val vaultKeystoreFile = System.getenv("VAULT_KEYSTORE_FILE")?.let { file(it) }
val vaultStorePassword = System.getenv("VAULT_KEYSTORE_PASSWORD")
val vaultKeyAlias = System.getenv("VAULT_KEY_ALIAS")
val vaultKeyPassword = System.getenv("VAULT_KEY_PASSWORD")
val hasStableDebugSigning = vaultKeystoreFile?.isFile == true &&
    !vaultStorePassword.isNullOrBlank() &&
    !vaultKeyAlias.isNullOrBlank() &&
    !vaultKeyPassword.isNullOrBlank()

android {
    namespace = "com.stealth.vaultnotes"
    compileSdk = 34

    defaultConfig {
        // Separate application ID so this edition installs beside the original.
        applicationId = "com.stealth.vaultsimple"
        minSdk = 26
        targetSdk = 34
        // CI run numbers increase monotonically, allowing in-place APK updates.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("stableDebug") {
            storeFile = vaultKeystoreFile ?: rootProject.file("missing-vaultsimple-signing-key.jks")
            storePassword = vaultStorePassword ?: ""
            keyAlias = vaultKeyAlias ?: "vaultsimple"
            keyPassword = vaultKeyPassword ?: ""
        }
        create("stableRelease") {
            storeFile = vaultKeystoreFile ?: rootProject.file("missing-vaultsimple-signing-key.jks")
            storePassword = vaultStorePassword ?: ""
            keyAlias = vaultKeyAlias ?: "vaultsimple"
            keyPassword = vaultKeyPassword ?: ""
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasStableDebugSigning) {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
        }
        release {
            signingConfig = signingConfigs.getByName("stableRelease")
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
