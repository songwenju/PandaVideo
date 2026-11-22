import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants { variant ->
                val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
                val publishApk = tasks.register<Copy>("publish${variantName}Apk") {
                    from(variant.artifacts.get(SingleArtifact.APK))
                    include("*.apk")
                    into(rootProject.layout.projectDirectory.dir("../releases"))
                    // Each application keeps one delivery APK; the latest assembled variant wins.
                    rename { if (project.name == "tv") "PandaVideo-TV.apk" else "PandaVideo.apk" }
                }
                tasks.matching { it.name == "assemble$variantName" }.configureEach {
                    dependsOn(publishApk)
                }
            }
        }
    }
}
