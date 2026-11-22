plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.pandavideo.tv"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.pandavideo.tv"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = providers.gradleProperty("tvTestRunner")
            .getOrElse("dev.pandavideo.tv.TvNetworkProbe")
        // Xiaomi Android 6 cleanup deletes sibling package paths sharing the app's prefix.
        testApplicationId = "dev.pandavideo.qa.tv"
    }
    buildFeatures { compose = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Local television delivery uses the same development certificate as debug,
            // allowing updates without deleting the viewer's history.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":shared"))
    implementation(libs.activity)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation("androidx.media3:media3-datasource-okhttp:1.9.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
}
