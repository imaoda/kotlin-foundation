import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}
kotlin {
    explicitApi()
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
    android {
        namespace = "io.github.imaoda.foundation.core"
        compileSdk = 37
        minSdk = 28
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
publishing {
    repositories { maven { name = "verification"; url = uri(rootProject.layout.buildDirectory.dir("repository")) } }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    providers.gradleProperty("iosDevice").orNull?.let { device.set(it) }
}
