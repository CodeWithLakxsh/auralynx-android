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

rootProject.name = "auralynx-android"

include(":app")
include(":core:common")
include(":core:protocol")
include(":core:automation")
include(":core:dsp")
include(":core:bluetooth")
include(":core:data")
include(":core:audio")
include(":core:designsystem")
include(":feature:translation")
include(":experimental:research")
