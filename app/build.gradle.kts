plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "xyz.oleolegka.gachimuchi"
    compileSdk = 37

    defaultConfig {
        applicationId = "xyz.oleolegka.gachimuchi"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    /*
     * Release signing. The key is NOT stored in the repository: CI writes it to a
     * temporary file and passes the path and the password through environment variables
     * (GitHub secrets). Locally those variables are absent, so a release build comes out
     * unsigned — that is expected.
     * The key must stay STABLE: Android only installs an update on top of an existing
     * app if it is signed with the same key, otherwise the app has to be uninstalled
     * along with its data.
     */
    val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_FILE")
    val keystorePass: String? = System.getenv("ANDROID_KEYSTORE_PASSWORD")

    signingConfigs {
        if (keystorePath != null && keystorePass != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePass
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "gachi"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: keystorePass
            }
        }
    }

    buildTypes {
        release {
            // R8 is off for now: shrinking can silently break Room/Compose, and there is
            // no device at hand to verify that it does not. Turn it on once there is.
            isMinifyEnabled = false
            if (keystorePath != null && keystorePass != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

/**
 * Robolectric downloads android-all from Maven Central by itself. If the machine cannot
 * reach it that way (a build behind a proxy, for instance), drop the jars into a
 * directory and point ROBOLECTRIC_DEPS_DIR at it — the tests then run offline. Without
 * the variable the behaviour is the default one.
 */
tasks.withType<Test>().configureEach {
    System.getenv("ROBOLECTRIC_DEPS_DIR")?.let { dir ->
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", dir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Room and the seed are verified by running them on the JVM (Robolectric); no emulator needed
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
