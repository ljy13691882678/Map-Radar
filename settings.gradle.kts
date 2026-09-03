pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    // Gradle 8.0+ 会自动把根目录 gradle/libs.versions.toml 暴露成 default "libs"
    // 版本目录，不要再手动 create("libs") { from(...) }，否则会触发：
    //   "Invalid catalog definition: you can only call the 'from' method a single time"
}

rootProject.name = "unicorn-realtime"
include(":sender")
include(":receiver")
