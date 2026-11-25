package com.abrah.nightmare.desktop.cli

import com.abrah.nightmare.desktop.core.ModelFile
import com.abrah.nightmare.desktop.core.ModelLibrary
import com.abrah.nightmare.desktop.core.ProductIdentity
import java.io.IOException
import java.util.Locale

/**
 * `models`: what is in the local model library.
 *
 * `models list` scans the root (see [ModelLibrary.resolve]) and prints every
 * model with the path `--model` takes, plus the files that are *not* models --
 * either because their bytes disagree with their name or because this build
 * cannot read their format. Nothing is silently skipped: a download that
 * finished at 60% must be a line of output, not an absence.
 *
 * ⚠ Headers only. Listing a 12-model cache reads a few megabytes, not 30 GB.
 */
internal object Models {

    const val NAME: String = "models"

    private const val LIST = "list"

    private val KNOWN_OPTIONS = setOf("dir")

    fun run(args: List<String>, out: Appendable, err: Appendable, env: Map<String, String>): Int {
        if (args.isEmpty()) {
            err.append(usageError("'$NAME' needs a subcommand"))
            return Cli.EXIT_USAGE
        }
        return when (val subcommand = args.first()) {
            LIST -> list(args.drop(1), out, err, env)
            "--help", "-h" -> {
                out.append(help())
                Cli.EXIT_OK
            }
            else -> {
                err.append(usageError("unrecognised subcommand '$subcommand'"))
                Cli.EXIT_USAGE
            }
        }
    }

    private fun list(args: List<String>, out: Appendable, err: Appendable, env: Map<String, String>): Int {
        // `list --help` is answered here rather than by [Options], which would
        // call it an unknown option -- and a usage message that points at a
        // command it cannot run is worse than no message.
        if (args.any { it == "--help" || it == "-h" }) {
            out.append(help())
            return Cli.EXIT_OK
        }
        val options = try {
            Options.parse(args, KNOWN_OPTIONS)
        } catch (e: UsageException) {
            err.append(usageError(e.message ?: "bad arguments"))
            return Cli.EXIT_USAGE
        }
        val dir = try {
            options.nonBlank("dir")
        } catch (e: UsageException) {
            err.append(usageError(e.message ?: "bad arguments"))
            return Cli.EXIT_USAGE
        }
        val scan = try {
            ModelLibrary.scan(ModelLibrary.resolve(dir, env))
        } catch (e: IOException) {
            // ⚠ A library that is not there is not an empty library: a script
            // that treats the two alike would silently generate from nothing.
            err.append(failure(e.message ?: "cannot read the model library"))
            return Cli.EXIT_FAILURE
        }
        out.append(render(scan))
        return Cli.EXIT_OK
    }

    private fun render(scan: ModelLibrary.Scan): String {
        if (scan.found.isEmpty() && scan.rejected.isEmpty() && scan.others.isEmpty()) {
            return buildString {
                append("${ProductIdentity.NAME}: no models under ${scan.root}\n")
                append(
                    "Download weights there, or point --dir / \$${ModelLibrary.ENV_MODELS} at the directory " +
                        "that holds them (see desktop/docs/models.md).\n",
                )
            }
        }

        val width = scan.found.maxOfOrNull { it.path.toString().length } ?: 0
        val text = StringBuilder()
        text.append(
            "${ProductIdentity.NAME}: ${scan.found.size} " +
                "${if (scan.found.size == 1) "model" else "models"} under ${scan.root}\n",
        )
        for (format in ModelFile.Format.values()) {
            val group = scan.found.filter { formatOf(it.contents) == format }
            if (group.isEmpty()) continue
            text.append('\n').append(format.extension).append(" (").append(group.size).append(")\n")
            for (model in group) {
                text.append("  ").append(model.path.toString().padEnd(width))
                    .append("  ").append(describe(model.contents)).append('\n')
            }
        }
        if (scan.rejected.isNotEmpty()) {
            text.append("\nrejected (").append(scan.rejected.size).append(") -- the extension says model, the bytes do not\n")
            for (rejected in scan.rejected) {
                text.append("  ").append(rejected.path).append("  ").append(rejected.reason).append('\n')
            }
        }
        if (scan.others.isNotEmpty()) {
            text.append("\nnot a model (").append(scan.others.size).append(") -- a format this build does not read\n")
            for (other in scan.others) {
                text.append("  ").append(other).append('\n')
            }
        }
        text.append("\nPass any of these to --model, e.g. ")
            .append("${ProductIdentity.NAME} txt2img --model <path> --prompt <text> --out <png>\n")
        return text.toString()
    }

    private fun formatOf(contents: ModelFile.Contents): ModelFile.Format = when (contents) {
        is ModelFile.Contents.Safetensors -> ModelFile.Format.SAFETENSORS
        is ModelFile.Contents.Gguf -> ModelFile.Format.GGUF
    }

    private fun describe(contents: ModelFile.Contents): String = when (contents) {
        is ModelFile.Contents.Safetensors ->
            "${contents.tensors} tensors, ${bytes(contents.headerBytes)} header"
        is ModelFile.Contents.Gguf ->
            "GGUF v${contents.version}, ${contents.tensors} tensors"
    }

    /** `4.0 GiB (4265146304 B)` -- one number to read, one to compare against a download's size. */
    internal fun bytes(count: Long): String {
        if (count < 1024) return "$count B"
        val units = listOf("KiB", "MiB", "GiB", "TiB")
        var value = count / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return String.format(Locale.ROOT, "%.1f %s (%d B)", value, units[unit], count)
    }

    internal fun help(): String = """
        |${ProductIdentity.NAME} models -- the local model library
        |
        |Usage:
        |  ${ProductIdentity.NAME} models list [--dir <path>]
        |
        |Reads every .safetensors and .gguf header under the root and prints what
        |each file is. Files that are not what their extension claims, and files in
        |formats this build does not read, are listed too -- never skipped.
        |
        |list options:
        |      --dir <path>    Directory to scan. Defaults to ${'$'}${ModelLibrary.ENV_MODELS},
        |                      then ~/${ModelLibrary.DEFAULT_SUBPATH}.
        |
        |Exit codes:
        |  0  the library was listed (an empty library included)
        |  1  the library directory does not exist or cannot be read
        |  2  the command line was wrong
        |""".trimMargin()

    private fun usageError(reason: String): String =
        "${ProductIdentity.NAME}: $reason\n" +
            "Try '${ProductIdentity.NAME} $NAME list --help' for more information.\n"

    private fun failure(reason: String): String = "${ProductIdentity.NAME}: $reason\n"
}
