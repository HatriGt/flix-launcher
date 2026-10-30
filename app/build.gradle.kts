import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from local.properties (flix.*) or FLIX_* environment variables,
// never from the repo. Without them, release builds fall back to the debug key so anyone
// can still build and sideload, but those APKs can't update an officially signed install.
val signing = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(key: String) = signing.getProperty("flix.$key") ?: System.getenv("FLIX_" + key.uppercase())

android {
    namespace = "com.flix.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.flix.launcher"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
    }

    signingConfigs {
        create("release") {
            secret("storeFile")?.let { storeFile = rootProject.file(it) }
            storePassword = secret("storePassword")
            keyAlias = secret("keyAlias")
            keyPassword = secret("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            val release = signingConfigs.getByName("release")
            // Never ship a debug-signed release by accident: without the release key the build stops,
            // unless debug signing is asked for explicitly (CI test builds, which aren't published).
            signingConfig = when {
                release.storeFile?.exists() == true -> release
                project.hasProperty("allowDebugSigning") -> signingConfigs.getByName("debug")
                else -> throw GradleException("Release key not found: set flix.storeFile etc. in local.properties or FLIX_* env vars (or pass -PallowDebugSigning for a test build)")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
}
