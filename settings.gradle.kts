@file:Suppress("UnstableApiUsage")

rootProject.name = "kclip"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")

    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        mavenCentral()
    }
}

include(":cli")
include(":core:agent")
include(":core:application")
include(":core:diagnostics")
include(":core:domain")
include(":core:platform")
include(":core:protocol")
