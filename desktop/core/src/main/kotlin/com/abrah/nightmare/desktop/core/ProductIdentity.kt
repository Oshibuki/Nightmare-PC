package com.abrah.nightmare.desktop.core

/**
 * Who this binary is. The CLI prints [banner] for `--version`, and Phase 1 code
 * that reports a backend version reuses [NAME] so every message agrees.
 *
 * [VERSION] is generated from gradle/libs.versions.toml at build time, which is
 * why the class it lives in is internal: the catalog is the only place a human
 * should ever change the version.
 */
object ProductIdentity {

    const val NAME: String = "nightmare-desktop"

    val VERSION: String get() = BuildInfo.VERSION

    /** One line naming the product and its version, e.g. `nightmare-desktop 0.1.0`. */
    fun banner(): String = "$NAME $VERSION"
}
