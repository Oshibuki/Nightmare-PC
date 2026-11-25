package com.abrah.nightmare.desktop.cli

import com.abrah.nightmare.desktop.core.ModelFile
import com.abrah.nightmare.desktop.core.ProductIdentity
import com.abrah.nightmare.desktop.core.SamplerWire
import java.io.File
import java.io.Flushable
import java.io.IOException
import java.nio.file.Paths

/**
 * `img2img`: an init image and a prompt in, a transformed PNG out, by running
 * the vendored engine's `sd-cli` with `--init-img` and `--strength`.
 *
 * The engine already owns the diffusion -- this command owns the things the
 * engine cannot check cheaply:
 *
 * - **Input validation.** A missing, non-PNG, or wrongly-sized init image is
 *   rejected before a model is loaded. The UNet downsamples by 8, so an init
 *   image whose dimensions are not positive multiples of 8 would come back a
 *   different size than the one on disk, silently breaking reproducibility.
 * - **The parameter vocabulary.** Sampler, scheduler and strength are checked
 *   here, so a typo costs a millisecond instead of a multi-minute model load.
 * - **The metadata.** The A1111-style `parameters` chunk records the strength
 *   and the init image name, without which the output cannot be reproduced.
 *
 * ⚠ `--model` is checked against its own header before the engine starts (the
 * same rule [Txt2Img] applies): a half-downloaded checkpoint otherwise costs
 * minutes and ends in a crash or a noise image.
 */
internal object Img2Img {

    const val NAME: String = "img2img"

    /** Shared with [Txt2Img]: both commands run the same vendored binary. */
    const val DEFAULT_ENGINE: String = Txt2Img.DEFAULT_ENGINE
    const val ENV_ENGINE: String = Txt2Img.ENV_ENGINE

    private const val DEFAULT_STEPS = 20
    private const val DEFAULT_SEED = 42L
    private const val DEFAULT_CFG = 7.0
    private const val DEFAULT_STRENGTH = 0.75
    private const val RANDOM_SEED = -1L

    private const val SIZE_MULTIPLE = 8
    private val STEPS_RANGE = 1..150

    private val KNOWN_OPTIONS = setOf(
        "model", "prompt", "negative-prompt", "init-img", "strength",
        "width", "height", "steps", "sampler", "scheduler", "cfg-scale", "seed", "out", "engine",
    )

    fun run(args: List<String>, out: Appendable, err: Appendable, env: Map<String, String>): Int {
        val request = try {
            parse(args).also { requireLoadableModel(it.model) }
        } catch (e: UsageException) {
            err.append(usageError(e.message ?: "bad arguments"))
            return Cli.EXIT_USAGE
        }

        // Validate everything we can before paying for a model load: a typo in
        // --model or --init-img must not cost ten minutes of engine start-up.
        val modelError = modelProblem(request.model)
        if (modelError != null) {
            err.append(failure(modelError))
            return Cli.EXIT_FAILURE
        }

        val init = File(request.initImage)
        if (!init.isFile) {
            err.append(failure("init image '${request.initImage}' does not exist or is not a file"))
            return Cli.EXIT_FAILURE
        }
        if (!init.canRead()) {
            err.append(failure("init image '${request.initImage}' is not readable"))
            return Cli.EXIT_FAILURE
        }
        val initHeader = Png.header(init)
        if (initHeader == null) {
            err.append(failure("init image '${request.initImage}' is not a PNG file; img2img needs a PNG input"))
            return Cli.EXIT_FAILURE
        }
        if (!validSize(initHeader.width) || !validSize(initHeader.height)) {
            err.append(
                failure(
                    "init image '${request.initImage}' is ${initHeader.width}x${initHeader.height}; " +
                        "width and height must each be a positive multiple of $SIZE_MULTIPLE",
                ),
            )
            return Cli.EXIT_FAILURE
        }
        // Width/height are derived from the init image unless the caller says
        // otherwise; an explicit size is still checked for the same 8-pixel rule.
        val width = request.width ?: initHeader.width
        val height = request.height ?: initHeader.height

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

        // Even a single image honours --seed -1: the drawn seed is printed and
        // written into the PNG so the run can be reproduced later.
        val seed = if (request.seed == RANDOM_SEED) {
            kotlin.random.Random.nextLong(0L, Int.MAX_VALUE.toLong())
        } else {
            request.seed
        }

        out.append("seed=$seed -> ${outFile.path}\n")
        (out as? Flushable)?.flush()
        return generate(engine, request, outFile, seed, width, height, out, err)
    }

    /** One engine run, then the checks that make its exit code mean something. */
    private fun generate(
        engine: File,
        request: Request,
        target: File,
        seed: Long,
        width: Int,
        height: Int,
        out: Appendable,
        err: Appendable,
    ): Int {
        val code = try {
            runEngine(
                engine,
                listOf(
                    "-m", request.model,
                    "-p", request.prompt,
                    "-n", request.negativePrompt,
                    "--init-img", request.initImage,
                    "--strength", strengthText(request.strength),
                    "-W", width.toString(),
                    "-H", height.toString(),
                    "--steps", request.steps.toString(),
                    "--seed", seed.toString(),
                    "--sampling-method", request.sampler,
                    "--scheduler", request.scheduler,
                    "--cfg-scale", cfgText(request.cfgScale),
                    "-o", target.path,
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
        if (!target.isFile) {
            err.append(failure("engine exited with code 0 but wrote no image to '${target.path}'"))
            return Cli.EXIT_FAILURE
        }
        val header = Png.header(target)
        if (header == null) {
            err.append(failure("'${target.path}' is not a PNG file; the engine did not write an image there"))
            return Cli.EXIT_FAILURE
        }
        if (header.width != width || header.height != height) {
            err.append(failure("'${target.path}' is $header, expected ${width}x${height}"))
            return Cli.EXIT_FAILURE
        }

        if (!Png.addParameters(target, parameters(request, seed, width, height))) {
            err.append(failure("'${target.path}' is not a complete PNG; its parameters could not be recorded"))
            return Cli.EXIT_FAILURE
        }
        if (Png.header(target) != header) {
            err.append(failure("writing the parameters chunk corrupted '${target.path}'"))
            return Cli.EXIT_FAILURE
        }
        return Cli.EXIT_OK
    }

    /**
     * The A1111 `parameters` text. Strength and init image are recorded beside
     * the usual settings so a result can be reproduced from the PNG alone.
     */
    private fun parameters(request: Request, seed: Long, width: Int, height: Int): String = buildString {
        append(request.prompt)
        append('\n')
        if (request.negativePrompt.isNotEmpty()) {
            append("Negative prompt: ").append(request.negativePrompt).append('\n')
        }
        append("Steps: ").append(request.steps)
        append(", Sampler: ").append(SamplerWire.samplerLabel(request.sampler))
        append(", Schedule type: ").append(SamplerWire.schedulerLabel(request.scheduler))
        append(", CFG scale: ").append(cfgText(request.cfgScale))
        append(", Seed: ").append(seed)
        append(", Size: ").append(width).append('x').append(height)
        append(", Strength: ").append(strengthText(request.strength))
        append(", Init image: ").append(File(request.initImage).name)
        append(", Model: ").append(File(request.model).name)
    }

    /** `7.0` -> "7", `5.5` -> "5.5": the way every other SD tool writes these. */
    private fun cfgText(scale: Double): String =
        if (scale == scale.toLong().toDouble()) scale.toLong().toString() else scale.toString()

    private fun strengthText(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun validSize(value: Int): Boolean = value > 0 && value % SIZE_MULTIPLE == 0

    /** A path whose extension is not a readable model format is a command-line mistake. */
    private fun requireLoadableModel(path: String) {
        if (ModelFile.formatOf(Paths.get(path)) == null) {
            throw UsageException(
                "--model '${File(path).name}' is not a model file this build reads " +
                    "(supported: ${ModelFile.EXTENSIONS.joinToString(", ") { ".$it" }})",
            )
        }
    }

    /** `null` means the checkpoint is usable; otherwise the reason to print and fail with. */
    private fun modelProblem(path: String): String? {
        val model = File(path)
        if (!model.isFile) return "model '$path' does not exist or is not a file"
        if (!model.canRead()) return "model '$path' is not readable"
        val contents = try {
            ModelFile.read(model.toPath())
        } catch (e: IOException) {
            return "model '$path' is not usable: ${e.message}"
        }
        if (contents is ModelFile.Contents.Gguf) {
            return "model '$path' is a GGUF diffusion model; img2img loads a single-file " +
                ".safetensors checkpoint; nothing was generated"
        }
        return null
    }

    /** Everything the engine call needs, after the command line has been checked. */
    private class Request(
        val model: String,
        val initImage: String,
        val prompt: String,
        val negativePrompt: String,
        val strength: Double,
        val width: Int?,
        val height: Int?,
        val steps: Int,
        val seed: Long,
        val sampler: String,
        val scheduler: String,
        val cfgScale: Double,
        val out: String,
        val engine: String?,
    )

    private fun parse(args: List<String>): Request {
        val o = Options.parse(args, KNOWN_OPTIONS)
        return Request(
            model = o.required("model"),
            initImage = o.required("init-img"),
            out = o.required("out"),
            prompt = o["prompt"] ?: "",
            negativePrompt = o["negative-prompt"] ?: "",
            strength = strength(o["strength"]),
            width = o["width"]?.let { size("width", it) },
            height = o["height"]?.let { size("height", it) },
            steps = intIn("steps", o["steps"], DEFAULT_STEPS, STEPS_RANGE),
            seed = o["seed"]?.let { raw ->
                raw.toLongOrNull() ?: throw UsageException("option '--seed' needs an integer, got '$raw'")
            } ?: DEFAULT_SEED,
            sampler = oneOf("sampler", o["sampler"], SamplerWire.DEFAULT_SAMPLER, SamplerWire.SAMPLERS),
            scheduler = oneOf("scheduler", o["scheduler"], SamplerWire.DEFAULT_SCHEDULER, SamplerWire.SCHEDULERS),
            cfgScale = positiveDouble("cfg-scale", o["cfg-scale"], DEFAULT_CFG),
            engine = o["engine"],
        )
    }

    private fun strength(raw: String?): Double {
        if (raw == null) return DEFAULT_STRENGTH
        val value = raw.toDoubleOrNull()
            ?: throw UsageException("option '--strength' needs a number, got '$raw'")
        if (!value.isFinite() || value < 0.0 || value > 1.0) {
            throw UsageException("option '--strength' must be between 0 and 1, got '$raw'")
        }
        return value
    }

    private fun size(name: String, raw: String): Int {
        val value = raw.toIntOrNull()
            ?: throw UsageException("option '--$name' needs an integer, got '$raw'")
        if (!validSize(value)) {
            throw UsageException("option '--$name' must be a positive multiple of $SIZE_MULTIPLE, got $value")
        }
        return value
    }

    private fun intIn(name: String, raw: String?, default: Int, range: IntRange): Int {
        if (raw == null) return default
        val value = raw.toIntOrNull()
            ?: throw UsageException("option '--$name' needs an integer, got '$raw'")
        if (value !in range) {
            throw UsageException("option '--$name' must be between ${range.first} and ${range.last}, got $value")
        }
        return value
    }

    private fun positiveDouble(name: String, raw: String?, default: Double): Double {
        if (raw == null) return default
        val value = raw.toDoubleOrNull()
            ?: throw UsageException("option '--$name' needs a number, got '$raw'")
        if (!value.isFinite() || value <= 0.0) {
            throw UsageException("option '--$name' must be positive, got '$raw'")
        }
        return value
    }

    private fun oneOf(name: String, raw: String?, default: String, allowed: List<String>): String {
        if (raw == null) return default
        if (raw !in allowed) {
            throw UsageException(
                "option '--$name' must be one of [${allowed.joinToString(", ")}], got '$raw'",
            )
        }
        return raw
    }

    /**
     * Runs the engine with its stdout and stderr merged onto [out], draining
     * the pipe while the process runs (waiting first deadlocks a chatty child).
     * No internal deadline: the caller's `timeout` bounds a legitimate slow run.
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
