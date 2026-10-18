import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.library) apply false
}
allprojects {
    group = "io.github.imaoda.foundation"
    version = "0.1.0-SNAPSHOT"
}

abstract class CheckBoundaries : DefaultTask() {
    @get:Input abstract val coreDependencies: ListProperty<String>
    @get:Input abstract val projectEdges: ListProperty<String>

    @TaskAction fun verify() {
        val unexpected = coreDependencies.get().distinct().filterNot {
            it.startsWith("org.jetbrains.kotlin:kotlin-stdlib")
        }
        val forbiddenEdges = projectEdges.get().distinct().filterNot {
            it in setOf(":examples -> :core", ":android-consumer -> :core")
        }
        check(unexpected.isEmpty() && forbiddenEdges.isEmpty()) {
            "Core must remain stdlib-only; declared forbidden dependencies: $unexpected; edges: $forbiddenEdges"
        }
        logger.lifecycle("Declared boundaries verified: stdlib-only core, consumers -> core.")
    }
}

val checkBoundaries = tasks.register<CheckBoundaries>("checkBoundaries") {
    group = "verification"
    coreDependencies.convention(emptyList())
    projectEdges.convention(emptyList())
}
subprojects {
    val ownerPath = path
    configurations.configureEach {
        val production = !name.contains("test", ignoreCase = true) &&
            listOf("api", "implementation", "compileOnly", "runtimeOnly").any { name.endsWith(it, ignoreCase = true) }
        if (production) dependencies.all {
            if (this is ProjectDependency) {
                val edge = "$ownerPath -> $path"
                checkBoundaries.configure { projectEdges.add(edge) }
            } else if (ownerPath == ":core") {
                val coordinate = "${group.orEmpty()}:$name"
                checkBoundaries.configure { coreDependencies.add(coordinate) }
            }
        }
    }
}

val checkDocs = tasks.register<Exec>("checkDocs") {
    group = "verification"
    commandLine("python3", "scripts/check-docs.py")
}
tasks.named("check") {
    dependsOn(checkBoundaries, checkDocs, ":core:jvmTest", ":core:checkKotlinAbi",
        ":examples:jvmTest", ":android-consumer:testDebugUnitTest", ":android-consumer:assembleDebug")
}
