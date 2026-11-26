plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ozma.android"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.ozma.android"
        minSdk = 26
        // targetSdk 28 on purpose: Android 10+ blocks executing/mmap-exec of files in the app's data dir for
        // targetSdk >= 29, and we run a glibc loader + binary from there. Sideload only (not Play Store).
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("zip") }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
