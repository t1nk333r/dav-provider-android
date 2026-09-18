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
        // dav4jvm is not on Maven Central; it is published on JitPack only.
        // Scoped to the one group so nothing else can resolve from JitPack.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.bitfireAT") }
        }
    }
}

rootProject.name = "dav-provider-android"
include(":app")
