import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing is read from a git-ignored keystore.properties (see
// keystore.properties.template). When absent — CI, fresh clones — the release
// build falls back to debug signing so it still assembles for verification.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}
val hasReleaseKeystore = keystorePropertiesFile.exists()

android {
    namespace = "com.eladkay.vibeview"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eladkay.vibeview"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Two distributions of the same app. They differ only in the idle-screen subtitle:
    // "home" carries a personal label, "production" is the neutral build for release.
    // The default strings in src/main are the production wording; src/home overrides.
    flavorDimensions += "distribution"

    productFlavors {
        create("home") {
            dimension = "distribution"
            versionNameSuffix = "-home"
        }
        create("production") {
            dimension = "distribution"
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
        // Media3's ExoPlayer/PlayerView surface is annotated @UnstableApi
        // (@RequiresOptIn level=ERROR); opt in module-wide.
        freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi"
    }

    // The APK ships no third-party dependency metadata block (keeps Play from
    // flagging the reverse-engineered protocol libraries' transitive metadata).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
            )
        }
    }
}

dependencies {
    implementation(project(":airplay"))
    implementation(project(":dlna"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.preference)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.slf4j.android)
}
