package com.abrah.nightmare.desktop.cli

import kotlin.system.exitProcess

/**
 * Process entry point. Everything interesting lives in [Cli]; this only wires
 * the real streams in and turns the returned code into a process exit status.
 */
fun main(args: Array<String>) {
    val code = Cli.run(args.toList(), System.out, System.err)
    System.out.flush()
    System.err.flush()
    if (code != Cli.EXIT_OK) exitProcess(code)
}
