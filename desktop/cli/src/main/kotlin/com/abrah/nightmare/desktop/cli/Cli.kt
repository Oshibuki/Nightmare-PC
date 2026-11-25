package com.abrah.nightmare.desktop.cli

import com.abrah.nightmare.desktop.core.ProductIdentity

/**
 * The command line surface: identity, usage, and the Phase 0 `txt2img` command.
 * Argument handling is a pure function of (args, environment, streams) so it
 * can be tested without spawning a process, and so [Main] stays a two-line
 * adapter.
 *
 * Exit codes are part of the contract and are documented in `--help`:
 * [EXIT_OK] the request was served, [EXIT_FAILURE] the request was understood
 * but could not be completed (missing model, engine error, unusable output),
 * [EXIT_USAGE] the command line itself was wrong. Each failure says why on
 * stderr -- a generation command that fails silently is worse than useless to a
 * script.
 */
object Cli {

    const val EXIT_OK: Int = 0
    const val EXIT_FAILURE: Int = 1
    const val EXIT_USAGE: Int = 2

    fun run(args: List<String>, out: Appendable, err: Appendable): Int =
        run(args, out, err, System.getenv())

    /** [env] is a parameter rather than a global read so tests can pose as a shell. */
    fun run(args: List<String>, out: Appendable, err: Appendable, env: Map<String, String>): Int {
        if (args.isEmpty()) {
            err.append(usageError("no command given"))
            return EXIT_USAGE
        }

        // `--help` and `--version` stay one-token commands; anything else that
        // starts with a dash is a mistake, never a silent no-op. A script that
        // mistypes a flag must not believe it generated anything.
        return when (val first = args.first()) {
            "--help", "-h" -> {
                out.append(help())
                EXIT_OK
            }
            "--version" -> {
                out.append(ProductIdentity.banner()).append('\n')
                EXIT_OK
            }
            Txt2Img.NAME -> Txt2Img.run(args.drop(1), out, err, env)
            else -> {
                err.append(usageError("unrecognised argument '$first'"))
                EXIT_USAGE
            }
        }
    }

    private fun usageError(reason: String): String =
        "${ProductIdentity.NAME}: $reason\n" +
            "Try '${ProductIdentity.NAME} --help' for more information.\n"

    internal fun help(): String =
        """
        |${ProductIdentity.banner()}
        |Headless image generation for Linux, Windows and macOS.
        |
        |Usage:
        |  ${ProductIdentity.NAME} <option>
        |  ${ProductIdentity.NAME} txt2img --model <path> --prompt <text> --out <path> [options]
        |
        |Options:
        |  -h, --help     Show this help and exit.
        |      --version  Print the product version and exit.
        |
        |Commands:
        |  txt2img        Generate one image from a prompt with the vendored engine.
        |
        |txt2img options:
        |      --model <path>              Model file to load (required).
        |      --prompt <text>             Prompt to render (required).
        |      --out <path>                PNG file to write (required).
        |      --negative-prompt <text>    Negative prompt (default: empty).
        |      --width <int>               Image width in pixels (default: 512).
        |      --height <int>              Image height in pixels (default: 512).
        |      --steps <int>               Sampling steps (default: 20).
        |      --seed <int>                RNG seed (default: 42).
        |      --engine <path>             sd-cli binary to run. Defaults to
        |                                  ${'$'}NIGHTMARE_SD_BIN, then ${Txt2Img.DEFAULT_ENGINE}.
        |
        |Exit codes:
        |  0  the image was generated
        |  1  generation failed (missing model, engine error, unusable output)
        |  2  the command line was wrong
        |
        |Model weights are not committed; see desktop/docs/models.md.
        |""".trimMargin()
}
