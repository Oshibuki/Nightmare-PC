plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.abrah.nightmare.desktop.cli.MainKt")
    // `installDist` then produces bin/nightmare-desktop, which is the command
    // the README and CI refer to.
    applicationName = "nightmare-desktop"
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}
