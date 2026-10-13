import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.play.publisher)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingSetting(name: String): String? =
    keystoreProperties.getProperty(name) ?: providers.gradleProperty("reader.$name").orNull

android {
    namespace = "cc.hafa.reader"
    compileSdk = 37

    defaultConfig {
        applicationId = "cc.hafa.reader"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
    }

    // storeFile, storePassword, keyAlias and keyPassword come from android/keystore.properties
    // (git-ignored), or from -Preader.<name> in CI. Without them release is debug-signed, which
    // installs locally but Play won't accept.
    val uploadStoreFile = signingSetting("storeFile")
    signingConfigs {
        if (uploadStoreFile != null) {
            create("upload") {
                storeFile = rootProject.file(uploadStoreFile)
                storePassword = signingSetting("storePassword")
                keyAlias = signingSetting("keyAlias")
                keyPassword = signingSetting("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (uploadStoreFile != null) "upload" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    lint {
        warningsAsErrors = true
        // Version nags appear on their own as time passes; Dependabot handles them.
        disable += setOf("OldTargetApi", "GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

ksp {
    arg("room.generateKotlin", "true")
    // The current version's schema is kept in the repository, to write and test the next migration against.
    arg("room.schemaLocation", "$projectDir/schemas")
}

// publishReleaseBundle uploads to Play; credentials come from ANDROID_PUBLISHER_CREDENTIALS (.github/workflows/android-publish.yml).
play {
    track.set("internal")
    defaultToAppBundles.set(true)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
}
