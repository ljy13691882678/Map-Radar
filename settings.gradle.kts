pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Compose Multiplatform Gradle plugin 也在这里分发
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Compose Multiplatform runtime
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "unicorn-realtime"
include(":sender")
include(":receiver")
include(":desktop")
