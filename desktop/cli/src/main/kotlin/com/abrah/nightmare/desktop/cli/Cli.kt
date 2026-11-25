package com.abrah.nightmare.desktop.cli

import com.abrah.nightmare.desktop.core.ProductIdentity

/**
 * The whole command line surface for Phase 0: identity and usage, no generation
 * yet. Argument parsing is a pure function of (args, streams) so it can be
 * tested without spawning a process, and so [Main] stays a two-line adapter.
 *
 * Exit codes: [EXIT_OK] when the request was served, [EXIT_USAGE] when the
 * command line itself was wrong. Anything that fails later (missing model,
 * engine error) must pick a distinct non-zero code and say why on stderr.
 */
object Cli {

    const val EXIT_OK: Int = 0
    const val EXIT_USAGE: Int = 2

    fun run(args: List<String>, out: Appendable, err: Appendable): Int {
        if (args.isEmpty()) {
            err.append(usageError("no command given"))
            return EXIT_USAGE
        }

        // One argument is the whole grammar until subcommands exist. An
        // unrecognised argument is an error rather than a silent no-op: a
        // script that mistypes a flag must not believe it generated anything.
        return when (val arg = args.single()) {
            "--help", "-h" -> {
                out.append(help())
                EXIT_OK
            }
            "--version" -> {
                out.append(ProductIdentity.banner()).append('\n')
                EXIT_OK
            }
            else -> {
                err.append(usageError("unrecognised argument '$arg'"))
                EXIT_USAGE
            }
        }
    }

    private fun usageError(reason: String): String =
        "${ProductIdentity.NAME}: $reason\n" +
            "Try '${ProductIdentity.NAME} --help' for more information.\n"

    private fun help(): String =
        """
        |${ProductIdentity.banner()}
        |Headless image generation for Linux, Windows and macOS.
        |
        |Usage: ${ProductIdentity.NAME} <option>
        |
        |Options:
        |  -h, --help     Show this help and exit.
        |      --version  Print the product version and exit.
        |
        |Generation commands (txt2img, img2img) arrive in a later phase; until
        |then this binary only reports what it is.
        |""".trimMargin()
}
