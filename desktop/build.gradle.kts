// Root of the Nightmare Desktop build. It applies nothing to itself: the two
// JVM modules own their own plugins, so a broken :cli cannot stop :core from
// compiling and neither can leak Android plugins into this build.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

// Product coordinates in one place. `version` feeds :core's generated
// BuildInfo, which is what `--version` prints. Read outside the block: the
// `libs` accessor only exists in the script body, not inside `allprojects`.
val productVersion = libs.versions.nightmare.get()

allprojects {
    group = "com.abrah.nightmare"
    version = productVersion
}
