import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { alias(libs.plugins.kotlin.multiplatform) }
kotlin {
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies { implementation("io.github.imaoda.foundation:core:0.1.0-SNAPSHOT") }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    providers.gradleProperty("iosDevice").orNull?.let { device.set(it) }
}
