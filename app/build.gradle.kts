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
        versionCode = 8
        versionName = "0.5.2"
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
        /*
         * BuildConfig is generated for one reason: the settings tab shows the version that
         * is installed. Updates arrive through Obtainium rather than a store, so there is
         * no listing anywhere saying what is on the phone, and a bug report about a version
         * nobody can name is a bug report about nothing.
         */
        buildConfig = true
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

    /*
     * Gradle gives a test JVM 512 MB by default, which is plenty for the reducer tests and
     * not enough for the screen tests: laying out text under Robolectric goes through
     * ShadowLineBreaker, which registers every native object it fakes in a registry that is
     * never swept, so a few dozen composed screens exhaust the heap and the tests start
     * failing with OutOfMemoryError in whatever ran last — a failure that says nothing
     * whatever about the code under test.
     */
    maxHeapSize = "2g"
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

    /*
     * Room runs over the TEST sources too, so that the migration test can declare the
     * previous schema as a real @Database and have Room generate its DDL. Writing the old
     * CREATE TABLE statements by hand would test the migration against a schema invented
     * for the test rather than against the one version 1 actually shipped.
     */
    kspTest(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Room and the screens are verified by running them on the JVM (Robolectric); no emulator needed
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    /*
     * Compose screens are tested on the JVM as well: `createComposeRule` under the
     * Robolectric runner, so `./gradlew test` covers them and there is no second command
     * and no device. The BOM has to be repeated on the test classpath — a platform applies
     * to one configuration only, and without it ui-test-junit4 would arrive versionless.
     */
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    /*
     * `createComposeRule` launches a ComponentActivity, which has to be DECLARED in the
     * manifest Robolectric reads — and that is the app's own debug manifest, not the test
     * one. This artifact is nothing but that declaration, which is why it is
     * debugImplementation (as Google documents it) rather than testImplementation: on the
     * test configuration the activity would never reach the merged manifest and every
     * screen test would die with "unable to resolve activity".
     */
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
