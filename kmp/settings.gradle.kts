pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "afm"

includeBuild("../deps/spirit2/kmp") {
    dependencySubstitution {
        substitute(module("blue.rae.spirit:spirit-sdk")).using(project(":sdk"))
    }
}
include(":app")
include(":app:androidApp")
include(":app:desktopApp")
include(":app:shared")
include(":app:webApp")
