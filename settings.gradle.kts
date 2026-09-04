pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "lsp-edit"

include(":lsp-edit")
include(":examples:common")
include(":examples:editor")
include(":examples:diff")
include(":examples:kotlinlsp")
include(":examples:syntax")
