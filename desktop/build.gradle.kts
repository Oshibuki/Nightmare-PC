// Root of the Nightmare Desktop build. It applies nothing to itself: each JVM
// module owns its plugins, so a broken :cli cannot stop :core or :gui from
// compiling and neither can leak Android plugins into this build.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.jetbrains.compose) apply false
}

// Product coordinates in one place. `version` feeds :core's generated
// BuildInfo, which is what `--version` prints. Read outside the block: the
// `libs` accessor only exists in the script body, not inside `allprojects`.
val productVersion = libs.versions.nightmare.get()

allprojects {
    group = "com.abrah.nightmare"
    version = productVersion
}
