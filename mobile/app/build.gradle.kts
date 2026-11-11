import com.android.build.api.variant.impl.VariantOutputImpl

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "dev.pandavideo"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.pandavideo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    flavorDimensions += "region"
    productFlavors { create("china") { dimension = "region" } }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            // AGP 9.1 exposes the filename property only on its output implementation.
            // Set it before packaging so output metadata and install tasks use the same name.
            (output as VariantOutputImpl).outputFileName.set("pandavideo.apk")
        }
    }
}
dependencies {
    implementation(project(":shared"))
    implementation(libs.activity)
}
