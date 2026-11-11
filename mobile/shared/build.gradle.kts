plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}
compose.resources { packageOfResClass = "dev.pandavideo.resources" }
kotlin {
    android {
        namespace = "dev.pandavideo.shared"
        compileSdk = 36
        minSdk = 26
        androidResources.enable = true
        withHostTestBuilder { }.configure { }
        withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    jvm {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        // GUI Xcode builds can leave a second Kotlin/Native distribution in ~/.konan while
        // KONAN_DATA_DIR points at /tmp/pandavideo-konan; tolerate the duplicated platform klibs.
        target.compilations.all {
            compilerOptions.configure {
                freeCompilerArgs.add("-Xklib-duplicated-unique-name-strategy=allow-first-with-warning")
            }
        }
        target.binaries.framework {
            baseName = "SuiXinKanShared"
            isStatic = true
            binaryOption("bundleId", "dev.pandavideo.shared")
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.animation)
            implementation(compose.components.resources)
            implementation(libs.navigation)
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.6")
            implementation(libs.coroutines)
            implementation(libs.serialization)
            implementation(libs.ktor.core)
            implementation(libs.coil.compose)
            implementation(libs.coil.network)
            implementation("com.squareup.okio:okio:3.16.2")
            implementation("androidx.sqlite:sqlite-bundled:2.6.2")
        }
        androidMain.dependencies {
            implementation(libs.activity)
            implementation(libs.ktor.okhttp)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.hls)
            implementation(libs.media3.ui)
            implementation(libs.media3.session)
            implementation(libs.media3.inspector)
            implementation(libs.media3.effect)
        }
        iosMain.dependencies { implementation(libs.ktor.darwin) }
        jvmMain.dependencies {
            implementation(libs.ktor.okhttp)
            // JavaFX Media uses AVFoundation on macOS, covering both HLS and MP4.
            // Classifier artifacts bundle the native dylibs and run from the classpath.
            val fxClassifier = if (System.getProperty("os.arch") == "aarch64") "mac-aarch64" else "mac"
            listOf("base", "graphics", "controls", "media", "swing").forEach { module ->
                implementation("org.openjfx:javafx-$module:${libs.versions.javafx.get()}:$fxClassifier")
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.mock)
            implementation(libs.coroutines.test)
        }
        getByName("androidDeviceTest").dependencies {
            implementation("androidx.test:runner:1.7.0")
            implementation("androidx.test.ext:junit:1.3.0")
        }
        jvmTest.dependencies {
            // Exercise the production Coil decoder with the host's actual Skia native library.
            implementation(compose.desktop.currentOs)
        }
    }
}
