import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

/**
 * Social configuration, supplied at build time from Gradle properties (e.g. in
 * ~/.gradle/gradle.properties) or environment variables (CI repository variables). None of these
 * are secrets. When they're empty the app works as a personal alarm clock and the Friends
 * section explains that it isn't set up yet.
 */
fun socialConfig(property: String, env: String): String =
    providers.gradleProperty(property).orElse(providers.environmentVariable(env)).getOrElse("")
        .replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "app.syncwake"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.syncwake"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "API_BASE_URL", "\"${socialConfig("syncwake.apiUrl", "SYNCWAKE_API_URL")}\"")
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"${socialConfig("syncwake.googleServerClientId", "SYNCWAKE_GOOGLE_SERVER_CLIENT_ID")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${socialConfig("syncwake.firebaseAppId", "SYNCWAKE_FIREBASE_APP_ID")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${socialConfig("syncwake.firebaseApiKey", "SYNCWAKE_FIREBASE_API_KEY")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${socialConfig("syncwake.firebaseProjectId", "SYNCWAKE_FIREBASE_PROJECT_ID")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${socialConfig("syncwake.firebaseSenderId", "SYNCWAKE_FIREBASE_SENDER_ID")}\"")
    }

    signingConfigs {
        // A fixed, committed debug key so every debug build (local or CI) has the same signature:
        // updates install over each other, and the SHA-1 registered for Google Sign-In stays valid.
        // Debug-only; release builds must use a private key that is never committed.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // Lint errors fail the build; warnings are reported but do not.
        abortOnError = true
        checkDependencies = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation("app.syncwake:domain")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.okhttp)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.work.runtime)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
