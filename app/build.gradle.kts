import java.io.File
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional release signing, supplied through GitHub Actions secrets (or local env vars).
val signingKeystoreBase64 = System.getenv("SIGNING_KEYSTORE_BASE64")?.trim().orEmpty()
val signingKeystorePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD").orEmpty()
val signingKeyAlias = System.getenv("SIGNING_KEY_ALIAS").orEmpty()
val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD").orEmpty()
val hasSigningSecrets = signingKeystoreBase64.isNotEmpty() &&
    signingKeystorePassword.isNotEmpty() &&
    signingKeyAlias.isNotEmpty()

// A shared debug keystore is committed on purpose (standard "android" debug credentials). Without
// it every CI run would sign with a fresh random key, and Android refuses to install a new build
// over the previous one ("App not installed") because the signatures differ.
val sharedDebugKeystore = file("debug.keystore")

android {
    namespace = "com.salvia.salviabrowxer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.salvia.salviabrowxer"
        minSdk = 26
        targetSdk = 35
        versionCode = 10000
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (sharedDebugKeystore.exists()) {
            getByName("debug") {
                storeFile = sharedDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (hasSigningSecrets) {
            create("release") {
                val decodedFile = File(rootProject.layout.buildDirectory.asFile.get(), "release-keystore.jks")
                decodedFile.parentFile?.mkdirs()
                val cleaned = signingKeystoreBase64.replace("\\s".toRegex(), "")
                decodedFile.writeBytes(Base64.getDecoder().decode(cleaned))
                storeFile = decodedFile
                storePassword = signingKeystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword.ifEmpty { signingKeystorePassword }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Real key when the secrets exist; otherwise the shared debug key so the release APK is
            // still installable on a phone (it just is not suitable for the Play Store).
            if (hasSigningSecrets) {
                signingConfig = signingConfigs.getByName("release")
            } else if (sharedDebugKeystore.exists()) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ""
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

    buildFeatures {
        compose = true
    }

    lint {
        // Lint is advisory here: a style/API warning must never stop an APK from being produced,
        // and lintVital would otherwise run (and can abort) inside assembleRelease.
        abortOnError = false
        checkReleaseBuilds = false
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    testImplementation("junit:junit:4.13.2")
}
