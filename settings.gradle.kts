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
        // NextLib 0.8.4（Media3 1.5.1 对应的 FFmpeg 软解扩展）发布在 JitPack
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "PocketPlayer"
include(":app")
