package com.abrah.nightmare.desktop.cli

import com.abrah.nightmare.desktop.core.ProductIdentity
import java.io.File
import java.io.Flushable
import java.io.IOException

/**
 * `txt2img`: prompt in, PNG out, by running the vendored engine's `sd-cli` as a
 * child process. Phase 0 keeps this deliberately thin -- one generation, one
 * file -- because its job is to prove the "edit engine -> build -> smoke" loop
 * end to end, not to be the Phase 1 command surface.
 *
 * The engine is a separate process, not a library: it prints progress as it
 * goes, it can crash without taking this JVM with it, and the contract between
 * the two is a command line plus a PNG file, which is exactly what the tests
 * can fake.
 */
internal object Txt2Img {

    const val NAME: String = "txt2img"

    /** Where `desktop/engine/build.sh` leaves the CPU binary, relative to the repo root. */
    const val DEFAULT_ENGINE: String = "desktop/engine/build/bin/sd-cli"

    const val ENV_ENGINE: String = "NIGHTMARE_SD_BIN"

    private const val DEFAULT_SIZE = 512
    private const val DEFAULT_STEPS = 20
    private const val DEFAULT_SEED = 42L

    private val KNOWN_OPTIONS = setOf(
        "model", "prompt", "negative-prompt", "width", "height",
        "steps", "seed", "out", "engine",
    )

    fun run(args: List<String>, out: Appendable, err: Appendable, env: Map<String, String>): Int {
        val request = try {
            parse(args)
        } catch (e: UsageException) {
            err.append(usageError(e.message ?: "bad arguments"))
            return Cli.EXIT_USAGE
        }

        // Check what we can check ourselves before paying for a model load: a
        // typo in --model must not cost ten minutes of engine start-up.
        val model = File(request.model)
        if (!model.isFile) {
            err.append(failure("model '${request.model}' does not exist or is not a file"))
            return Cli.EXIT_FAILURE
        }
        val engine = File(request.engine ?: env[ENV_ENGINE] ?: DEFAULT_ENGINE)
        if (!engine.isFile) {
            err.append(
                failure(
                    "engine '$engine' not found; build it with desktop/engine/build.sh " +
                        "or point --engine / \$$ENV_ENGINE at an sd-cli binary",
                ),
            )
            return Cli.EXIT_FAILURE
        }
        if (!engine.canExecute()) {
            err.append(failure("engine '$engine' is not executable"))
            return Cli.EXIT_FAILURE
        }
        val outFile = File(request.out)
        outFile.absoluteFile.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                err.append(failure("cannot create output directory '$parent'"))
                return Cli.EXIT_FAILURE
            }
        }

        val code = try {
            runEngine(
                engine,
                listOf(
                    "-m", request.model,
                    "-p", request.prompt,
                    "-n", request.negativePrompt,
                    "-W", request.width.toString(),
                    "-H", request.height.toString(),
                    "--steps", request.steps.toString(),
                    "--seed", request.seed.toString(),
                    "-o", request.out,
                ),
                out,
            )
        } catch (e: IOException) {
            err.append(failure("cannot run engine '$engine': ${e.message}"))
            return Cli.EXIT_FAILURE
        }
        if (code != 0) {
            err.append(failure("engine '$engine' exited with code $code"))
            return Cli.EXIT_FAILURE
        }

        // A zero exit is a claim, not proof. Verify the file before reporting
        // success, so a caller that only checks the exit code never gets a
        // broken PNG.
        if (!outFile.isFile) {
            err.append(failure("engine exited with code 0 but wrote no image to '${request.out}'"))
            return Cli.EXIT_FAILURE
        }
        val header = Png.header(outFile)
        if (header == null) {
            err.append(failure("'${request.out}' is not a PNG file; the engine did not write an image there"))
            return Cli.EXIT_FAILURE
        }
        if (header.width != request.width || header.height != request.height) {
            err.append(
                failure("'${request.out}' is $header, expected ${request.width}x${request.height}"),
            )
            return Cli.EXIT_FAILURE
        }
        return Cli.EXIT_OK
    }

    /** Everything the engine call needs, after the command line has been checked. */
    private class Request(
        val model: String,
        val prompt: String,
        val negativePrompt: String,
        val width: Int,
        val height: Int,
        val steps: Int,
        val seed: Long,
        val out: String,
        val engine: String?,
    )

    private class UsageException(message: String) : Exception(message)

    private fun parse(args: List<String>): Request {
        val options = parseOptions(args)
        return Request(
            model = options.required("model"),
            prompt = options.required("prompt"),
            out = options.required("out"),
            negativePrompt = options["negative-prompt"] ?: "",
            width = options.positiveInt("width", DEFAULT_SIZE),
            height = options.positiveInt("height", DEFAULT_SIZE),
            steps = options.positiveInt("steps", DEFAULT_STEPS),
            seed = options["seed"]?.let { raw ->
                raw.toLongOrNull() ?: throw UsageException("option '--seed' needs an integer, got '$raw'")
            } ?: DEFAULT_SEED,
            engine = options["engine"],
        )
    }

    private fun parseOptions(args: List<String>): Map<String, String> {
        val options = LinkedHashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val token = args[i]
            if (!token.startsWith("--")) {
                throw UsageException("unexpected argument '$token'")
            }
            val body = token.removePrefix("--")
            val equals = body.indexOf('=')
            if (equals >= 0) {
                val name = body.substring(0, equals)
                requireKnown(name)
                options[name] = body.substring(equals + 1)
                i += 1
            } else {
                requireKnown(body)
                if (i + 1 >= args.size) {
                    throw UsageException("option '--$body' needs a value")
                }
                options[body] = args[i + 1]
                i += 2
            }
        }
        return options
    }

    private fun requireKnown(name: String) {
        if (name !in KNOWN_OPTIONS) throw UsageException("unrecognised option '--$name'")
    }

    private fun Map<String, String>.required(name: String): String {
        val value = this[name] ?: throw UsageException("missing required option '--$name'")
        if (value.isBlank()) throw UsageException("option '--$name' must not be empty")
        return value
    }

    private fun Map<String, String>.positiveInt(name: String, default: Int): Int {
        val raw = this[name] ?: return default
        val value = raw.toIntOrNull()
            ?: throw UsageException("option '--$name' needs an integer, got '$raw'")
        if (value <= 0) throw UsageException("option '--$name' must be positive, got $value")
        return value
    }

    /**
     * Runs the engine with its stdout and stderr merged onto [out].
     *
     * The streams are drained while the process runs, never after `waitFor`:
     * waiting first is how a chatty child fills the pipe buffer and deadlocks
     * both processes. There is no internal deadline -- a CPU SD1.5 run is slow
     * and legitimate -- so the caller's `timeout` is what bounds it.
     */
    private fun runEngine(engine: File, engineArgs: List<String>, out: Appendable): Int {
        val process = ProcessBuilder(listOf(engine.path) + engineArgs)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(8192)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                out.append(String(buffer, 0, read))
                (out as? Flushable)?.flush()
            }
        }
        return process.waitFor()
    }

    private fun usageError(reason: String): String =
        "${ProductIdentity.NAME}: $reason\n" +
            "Try '${ProductIdentity.NAME} $NAME --help' for more information.\n"

    private fun failure(reason: String): String = "${ProductIdentity.NAME}: $reason\n"
}
