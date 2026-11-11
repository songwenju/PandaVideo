import org.gradle.api.JavaVersion
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            // JavaFX JFXPanel accesses SwingInterOpUtils from this optional JDK module.
            modules("jdk.unsupported.desktop")
            targetFormats(TargetFormat.Dmg)
            packageName = "熊猫视频"
            // jpackage requires MAJOR > 0; the mobile apps' internal 0.1.0 is not accepted here.
            packageVersion = "1.0.0"
            description = "熊猫视频桌面客户端"
            macOS {
                bundleID = "dev.pandavideo.desktop"
                iconFile.set(project.file("src/main/resources/pandavideo.icns"))
            }
        }
    }
}

dependencies {
    implementation(project(":shared"))
    // Skiko natives for the current host OS; JavaFX media natives arrive transitively via shared.
    implementation(compose.desktop.currentOs)
}
