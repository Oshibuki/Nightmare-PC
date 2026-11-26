package com.abrah.nightmare.desktop.gui

import kotlin.system.exitProcess

/**
 * Standalone entry point for `:gui:providerDiagnostics`. It wires the real
 * environment and stdout into [ProviderDiagnostics]; the tool itself stays a
 * pure function so tests never need a process or a provider address.
 */
fun main() {
    val result = ProviderDiagnostics().run(System.getenv())
    println(result.report)
    System.out.flush()
    if (result.exitCode != ProviderDiagnostics.EXIT_OK) exitProcess(result.exitCode)
}
