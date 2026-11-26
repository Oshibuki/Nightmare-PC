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
    // The copied agent core speaks org.json; the production ToolBox adapter in
    // main constructs the same JSON shapes (:core's implementation is not
    // transitive to this module's compile classpath).
    implementation(libs.json)
    // JVM state tests assert observable run state without a UI test framework.
    testImplementation(libs.junit)
    // Test doubles ported from the mobile agent use org.json directly; :core's
    // `implementation` is not on this module's compile classpath.
    testImplementation(libs.json)
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}

compose.desktop {
    application {
        mainClass = "com.abrah.nightmare.desktop.gui.MainKt"
    }
}
