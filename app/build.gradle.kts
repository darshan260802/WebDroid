import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Monotonic version code so every CI build installs over the previous one.
val gitCommitCount: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

// The VERSION file drives releases: CI publishes v<VERSION> the first time it
// builds a commit carrying a version that has no release yet.
val appVersionName: String = System.getenv("WEBDROID_VERSION_NAME")
    ?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }
    ?: rootProject.file("VERSION").takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() }
    ?: "0.1.0-dev.$gitCommitCount"

val releaseKeystore = System.getenv("WEBDROID_KEYSTORE_PATH")?.let { file(it) }
    ?: rootProject.file("keystore/release.jks")
val hasReleaseKeystore = releaseKeystore.exists()

android {
    namespace = "com.webdroid.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.webdroid.app"
        minSdk = 26
        targetSdk = 35
        versionCode = gitCommitCount
        versionName = appVersionName
        vectorDrawables.useSupportLibrary = true
        // OTA updates come from this repository's GitHub releases.
        val updateRepo = System.getenv("GITHUB_REPOSITORY")?.takeIf { it.isNotBlank() } ?: "darshan260802/WebDroid"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("WEBDROID_KEYSTORE_PASSWORD") ?: "webdroid-release"
                keyAlias = System.getenv("WEBDROID_KEY_ALIAS") ?: "webdroid"
                keyPassword = System.getenv("WEBDROID_KEY_PASSWORD") ?: "webdroid-release"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=app.cash.zipline.EngineApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.palette)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zipline.android)
}
