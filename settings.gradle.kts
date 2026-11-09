pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BodyBattery"
include(":core")
// BB_CORE_ONLY=1 builds only the pure-Kotlin core (no Android SDK needed).
if (System.getenv("BB_CORE_ONLY") == null) {
    include(":app")
}
