pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "kotlin-foundation"
include(":core", ":examples", ":android-consumer")
project(":core").projectDir = file("libraries/core")
project(":android-consumer").projectDir = file("verification/android-consumer")
