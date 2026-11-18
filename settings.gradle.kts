import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.repositories

include(":app", ":contract", ":carplay-engine", ":carplay-ui")
project(":carplay-engine").projectDir = file("carplay/engine")
project(":carplay-ui").projectDir = file("carplay/ui")


rootProject.name = "open-headunit"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        maven {
            url = uri("https://jitpack.io")
        }
    }
}
