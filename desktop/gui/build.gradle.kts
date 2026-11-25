plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    // The GUI and :cli share the same JVM model types; no Android code is used.
    implementation(project(":core"))
}

compose.desktop {
    application {
        mainClass = "com.abrah.nightmare.desktop.gui.MainKt"
    }
}
