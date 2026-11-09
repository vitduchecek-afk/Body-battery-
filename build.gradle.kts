buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        if (System.getenv("BB_CORE_ONLY") == null) {
            classpath("com.android.tools.build:gradle:8.11.1")
        }
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.21" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
}
