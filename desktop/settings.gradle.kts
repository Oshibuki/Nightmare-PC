// Nightmare Desktop -- a Gradle build of its own, deliberately unrelated to the
// Android build in the repository root (ADR-0003). Gradle run from `desktop/`
// treats this file as the build's root, so the root settings.gradle.kts and its
// `:app` project are never read. Keep it that way: nothing here may reference
// `app/`, and no Android plugin may be applied.
pluginManagement {
    repositories {
        // Plugin markers resolve from the portal and Maven Central; Google Maven
        // is needed only for Compose's generic androidx.* dependencies below.
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // Declaring a repository in a module build script is an error, so every
    // dependency resolves from here only.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Compose 1.12.2 resolves androidx.* common artifacts from Google Maven.
        // This is a repository, not an Android plugin or target.
        google()
        mavenCentral()
    }
}

rootProject.name = "nightmare-desktop"

// Responsibility areas: :cli (headless entry point), :core (shared JVM logic),
// :gui (Compose Desktop shell), engine/ (vendored C++ engine, built outside
// Gradle) and docs/ (desktop-only documentation). Only JVM modules are Gradle
// projects; no Android plugin or target is applied here.
include(":core", ":cli", ":gui")
