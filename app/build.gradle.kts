plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from the environment, never from the repo: CI decodes
// the keystore out of a secret and exports these three. Without them the
// release variant still builds, just unsigned, so a checkout without the key
// is not a broken checkout. Read through `providers` rather than
// System.getenv so the values are tracked configuration-cache inputs.
val releaseKeystorePath = providers.environmentVariable("KEYSTORE_PATH")
val releaseKeystorePassword = providers.environmentVariable("KEYSTORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("KEY_ALIAS")
val signRelease = releaseKeystorePath.isPresent

android {
    namespace = "com.qtotp.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.qtotp.mobile"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Pinned to a committed keystore, deliberately. These are the standard
        // debug credentials, identical on every Android install, so the file is
        // not a secret. What it buys is one stable debug identity: without it
        // each CI runner generates its own key, and installing a newer debug
        // APK over an older one fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
        // The usual workaround for that is uninstalling, which would take the
        // app-private vault with it.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        if (signRelease) {
            create("release") {
                storeFile = file(releaseKeystorePath.get())
                storePassword = releaseKeystorePassword.get()
                keyAlias = releaseKeyAlias.get()
                // The keystore is PKCS12, which keeps a single password for
                // the store and the key it holds.
                keyPassword = releaseKeystorePassword.get()
            }
        }
    }

    buildTypes {
        // A distinct application id so a debug build installs beside a release
        // one instead of replacing it. Replacing it would mean an uninstall -
        // different signing key - and an uninstall takes the vault with it.
        debug {
            applicationIdSuffix = ".debug"
        }

        release {
            signingConfig = if (signRelease) signingConfigs.getByName("release") else null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
