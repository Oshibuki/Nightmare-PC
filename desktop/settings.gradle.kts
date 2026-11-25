// Nightmare Desktop -- a Gradle build of its own, deliberately unrelated to the
// Android build in the repository root (ADR-0003). Gradle run from `desktop/`
// treats this file as the build's root, so the root settings.gradle.kts and its
// `:app` project are never read. Keep it that way: nothing here may reference
// `app/`, and no Android plugin may be applied.
pluginManagement {
    repositories {
        // No google() / mavenOnly-android repositories: desktop has no Android
        // dependency and must fail loudly if one sneaks in.
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // Declaring a repository in a module build script is an error, so every
    // dependency resolves from here only.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "nightmare-desktop"

// Four responsibility areas (ADR-0003): :cli (entry point), :core (logic),
// engine/ (vendored C++ engine, built outside Gradle) and docs/ (desktop-only
// documentation). Only the two JVM modules are Gradle projects.
include(":core", ":cli")
