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
}

rootProject.name = "tern-android"

// The companion protocol, in plain Kotlin: no Android in it, so its tests run on any JVM.
include(":protocol")

// The app: Bluetooth, the screens and what it keeps on the phone, over the protocol.
include(":app")
