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

rootProject.name = "Starmate"

include(":app")
include(":core:model", ":core:ui", ":core:data", ":core:llm", ":core:engine")
include(
    ":feature:chat",
    ":feature:memory",
    ":feature:diary",
    ":feature:proactive",
    ":feature:voice",
    ":feature:tools",
    ":feature:mcp",
    ":feature:mobileuse",
)
