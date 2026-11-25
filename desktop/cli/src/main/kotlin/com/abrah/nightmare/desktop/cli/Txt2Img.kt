package com.abrah.nightmare.desktop.cli

import com.abrah.nightmare.LoraSpec
import com.abrah.nightmare.PromptLoras
import com.abrah.nightmare.desktop.core.ModelFile
import com.abrah.nightmare.desktop.core.ModelLibrary
import com.abrah.nightmare.desktop.core.ProductIdentity
import com.abrah.nightmare.desktop.core.SamplerWire
import java.io.File
import java.io.Flushable
import java.io.IOException
import java.nio.file.Paths
import kotlin.random.Random

/**
 * `txt2img`: prompt in, PNG out, by running the vendored engine's `sd-cli` as a
 * child process.
 *
 * The engine is a separate process, not a library: it prints progress as it
 * goes, it can crash without taking this JVM with it, and the contract between
 * the two is a command line plus a PNG file, which is exactly what the tests
 * can fake.
 *
 * Two things this file owns that the engine does not:
 *
 * - **The parameter vocabulary.** `--sampler` and `--scheduler` are checked
 *   against [SamplerWire], which is the copied mobile table filtered down to the
 *   names the vendored engine's `sample_method_to_str[]` / `scheduler_to_str[]`
 *   actually contain. A typo therefore costs a millisecond, not a model load
 *   followed by `str_to_sample_method` returning `SAMPLE_METHOD_COUNT`.
 * - **The metadata.** The engine writes pixels; the CLI writes the A1111-style
 *   `parameters` chunk beside them, because a batch of PNGs with no record of
 *   the seed and sampler that made them cannot be reproduced.
 *
 * ⚠ `--model` is also checked against its own header before the engine starts
 * (see [ModelFile]): a half-downloaded checkpoint otherwise costs minutes of
 * model loading and ends in a crash or a noise image.
 */
internal object Txt2Img {

    const val NAME: String = "txt2img"

    /** Where `desktop/engine/build.sh` leaves the CPU binary, relative to the repo root. */
    const val DEFAULT_ENGINE: String = "desktop/engine/build/bin/sd-cli"

    const val ENV_ENGINE: String = "NIGHTMARE_SD_BIN"

    private const val DEFAULT_SIZE = 512
    private const val DEFAULT_STEPS = 20
    private const val DEFAULT_SEED = 42L
    private const val DEFAULT_CFG = 7.0
    private const val DEFAULT_BATCH = 1

    /**
     * The engine's UNet downsamples by 8, so a size that is not a multiple of 8
     * is silently rounded inside the engine and the PNG comes back a different
     * size than the one asked for. Refusing here keeps the CLI's promise exact.
     */
    private const val SIZE_MULTIPLE = 8

    /**
     * ⚠ Wider than the mobile widget's `Samplers.STEPS_RANGE` (1..50), which is a
     * slider bound, not a backend limit: on a CPU an agent may legitimately want
     * 100 steps, and the engine has no opinion. 150 is where a run stops being a
     * test and starts being a mistake.
     */
    private val STEPS_RANGE = 1..150

    /** One engine run per image, each with its own model decode: 16 is already hours. */
    private val BATCH_RANGE = 1..16

    /** `--seed -1`: draw one, so the metadata can say what was actually used. */
    private const val RANDOM_SEED = -1L

    private val KNOWN_OPTIONS = setOf(
        "model", "prompt", "negative-prompt", "width", "height",
        "steps", "sampler", "scheduler", "cfg-scale", "seed", "batch", "out", "engine",
        "lora", "lora-dir",
    )

    fun run(args: List<String>, out: Appendable, err: Appendable, env: Map<String, String>): Int {
        val request = try {
            parse(args, env).also { requireLoadableModel(it.model) }
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
        if (!model.canRead()) {
            err.append(failure("model '${request.model}' is not readable"))
            return Cli.EXIT_FAILURE
        }
        // The name is a claim; the header is the evidence. Refuse a GGUF here:
        // sd-cli's -m is a single-file checkpoint, so a DiT GGUF there renders noise.
        val contents = try {
            ModelFile.read(model.toPath())
        } catch (e: IOException) {
            err.append(failure("model '${request.model}' is not usable: ${e.message}"))
            return Cli.EXIT_FAILURE
        }
        if (contents is ModelFile.Contents.Gguf) {
            err.append(
                failure(
                    "model '${request.model}' is a GGUF diffusion model. Those are loaded with " +
                        "--diffusion-model (plus --llm and --vae), which this build does not implement yet; " +
                        "nothing was generated. Pass a single-file .safetensors checkpoint to --model.",
                ),
            )
            return Cli.EXIT_FAILURE
        }

        // sdcpp only WARNs and carries on when a <lora:...> name does not
        // resolve, so a typo would silently produce an image without the
        // adapter. Check every weight here instead: the engine is never
        // started for a LoRA request we cannot honour.
        val lora = prepareLoras(request, err) ?: return Cli.EXIT_FAILURE

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

        // A drawn seed is drawn ONCE for the whole batch and then incremented,
        // so the run is reproducible from the metadata of any one of its images.
        val baseSeed = if (request.seed == RANDOM_SEED) {
            Random.nextLong(0L, Int.MAX_VALUE.toLong())
        } else {
            request.seed
        }

        for (index in 1..request.batch) {
            val seed = baseSeed + (index - 1)
            val target = if (index == 1) outFile else numbered(outFile, index)
            out.append("[${index}/${request.batch}] seed=$seed -> ${target.path}\n")
            (out as? Flushable)?.flush()
            val failureCode = generate(engine, request, lora, target, seed, out, err)
            if (failureCode != Cli.EXIT_OK) return failureCode
        }
        return Cli.EXIT_OK
    }

    /**
     * The LoRA request after checking it against the weights on disk.
     *
     * [enginePrompt] carries the resolved `<lora:file:multiplier>` tags that
     * the pinned sdcpp CLI understands; [applied] is the same list in the
     * A1111 `file@multiplier` spelling used by the copied Android helper, for
     * the PNG's parameters. [dir] is null when no LoRA was requested, so a
     * plain run's engine command is byte-for-byte what it was before.
     */
    private class LoraPlan(
        val enginePrompt: String,
        val applied: List<LoraSpec.Entry>,
        val dir: String?,
    )

    /**
     * Resolve `--lora` entries and prompt-embedded tags against the LoRA
     * directory, then hand the engine exactly one tag per file. A prompt tag
     * wins over `--lora` for the same file, as it does on Android.
     *
     * Returns null after writing the reason to [err] -- a missing weight is a
     * failed request, not a usage error, because the command line itself was
     * well formed.
     */
    private fun prepareLoras(request: Request, err: Appendable): LoraPlan? {
        val split = PromptLoras.extract(request.prompt)
        if (request.loraEntries.isEmpty() && split.tags.isEmpty()) {
            return LoraPlan(request.prompt, emptyList(), null)
        }
        val dir = File(request.loraDir)
        if (!dir.isDirectory) {
            err.append(
                failure(
                    "LoRA directory '${dir.path}' does not exist or is not a directory; " +
                        "pass --lora-dir <path> pointing at the folder with the .safetensors weights",
                ),
            )
            return null
        }
        val installed = dir.listFiles()?.filter { it.isFile && !it.isHidden }?.map { it.name } ?: emptyList()

        val fromPrompt = mutableListOf<LoraSpec.Entry>()
        for (tag in split.tags) {
            val file = PromptLoras.resolve(tag.name, installed)
            if (file == null) {
                err.append(failure("LoRA '${tag.name}' in --prompt was not found in '${dir.path}'; nothing was generated"))
                return null
            }
            fromPrompt += LoraSpec.Entry(file, tag.strength)
        }
        // The same tokeniser as the mobile picker: one tag per file, prompt wins.
        val merged = LoraSpec.parse(PromptLoras.merge(LoraSpec.format(request.loraEntries), fromPrompt))
        val applied = mutableListOf<LoraSpec.Entry>()
        for (entry in merged) {
            val file = PromptLoras.resolve(entry.name, installed)
            if (file == null) {
                err.append(failure("LoRA weight '${entry.name}' was not found in '${dir.path}'; pass --lora-dir <path> or check the name"))
                return null
            }
            applied += LoraSpec.Entry(file, entry.strength)
        }
        val tags = applied.joinToString(" ") { "<lora:${it.name}:${LoraSpec.number(it.strength)}>" }
        val enginePrompt = if (split.text.isEmpty()) tags else "${split.text} $tags"
        return LoraPlan(enginePrompt, applied, dir.path)
    }

    /** One engine run, then the checks that make its exit code mean something. */
    private fun generate(
        engine: File,
        request: Request,
        lora: LoraPlan,
        target: File,
        seed: Long,
        out: Appendable,
        err: Appendable,
    ): Int {
        val code = try {
            runEngine(
                engine,
                listOf(
                    "-m", request.model,
                    "-p", lora.enginePrompt,
                    "-n", request.negativePrompt,
                    "-W", request.width.toString(),
                    "-H", request.height.toString(),
                    "--steps", request.steps.toString(),
                    "--seed", seed.toString(),
                    "--sampling-method", request.sampler,
                    "--scheduler", request.scheduler,
                    "--cfg-scale", cfgText(request.cfgScale),
                ) + (lora.dir?.let { listOf("--lora-model-dir", it) } ?: emptyList()) +
                    listOf("-o", target.path),
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
        if (header.width != request.width || header.height != request.height) {
            err.append(
                failure("'${target.path}' is $header, expected ${request.width}x${request.height}"),
            )
            return Cli.EXIT_FAILURE
        }

        // The parameters chunk is written by us, so it is verified by us too: a
        // chunk with a bad CRC32 would turn a good image into an unopenable one.
        if (!Png.addParameters(target, parameters(request, seed, lora.applied))) {
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
     * The A1111 `parameters` text: prompt, optional negative prompt, then one
     * line of settings. Human names, not wire values (`Euler A`, not `euler_a`),
     * because this string is what a person reads out of an image months later --
     * the exact values are all recoverable from the CLI flags that produced it.
     *
     * ⚠ `Model:` is here because this chunk **replaces** the one the engine
     * writes ([Png.addParameters]), and the engine's named the checkpoint. A
     * PNG that does not say which weights made it cannot be reproduced.
     */
    private fun parameters(request: Request, seed: Long, applied: List<LoraSpec.Entry>): String = buildString {
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
        append(", Size: ").append(request.width).append('x').append(request.height)
        append(", Model: ").append(File(request.model).name)
        if (applied.isNotEmpty()) {
            append(", Lora: ").append(LoraSpec.format(applied))
        }
    }

    /** `7.0` -> "7", `5.5` -> "5.5": the way every other SD tool writes a CFG scale. */
    private fun cfgText(scale: Double): String =
        if (scale == scale.toLong().toDouble()) scale.toLong().toString() else scale.toString()

    /** `out.png` + 2 -> `out-2.png`; the extension is optional, the directory is kept. */
    private fun numbered(outFile: File, index: Int): File {
        val name = outFile.name
        val stem = if (name.endsWith(".png", ignoreCase = true)) name.dropLast(4) else name
        return File(outFile.absoluteFile.parentFile, "$stem-$index.png")
    }

    /**
     * A path whose extension is not a format this build reads is a mistake in
     * the command line, not a broken file -- so it exits 2 without opening it.
     */
    private fun requireLoadableModel(path: String) {
        if (ModelFile.formatOf(Paths.get(path)) == null) {
            throw UsageException(
                "--model '${File(path).name}' is not a model file this build reads " +
                    "(supported: ${ModelFile.EXTENSIONS.joinToString(", ") { ".$it" }})",
            )
        }
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
        val sampler: String,
        val scheduler: String,
        val cfgScale: Double,
        val batch: Int,
        val out: String,
        val engine: String?,
        val loraEntries: List<LoraSpec.Entry>,
        val loraDir: String,
    )

    private class UsageException(message: String) : Exception(message)

    private fun parse(args: List<String>, env: Map<String, String>): Request {
        val options = parseOptions(args)
        return Request(
            model = options.required("model"),
            prompt = options.required("prompt"),
            out = options.required("out"),
            negativePrompt = options["negative-prompt"] ?: "",
            width = options.size("width", DEFAULT_SIZE),
            height = options.size("height", DEFAULT_SIZE),
            steps = options.intIn("steps", DEFAULT_STEPS, STEPS_RANGE),
            seed = options["seed"]?.let { raw ->
                raw.toLongOrNull() ?: throw UsageException("option '--seed' needs an integer, got '$raw'")
            } ?: DEFAULT_SEED,
            sampler = options.oneOf("sampler", SamplerWire.DEFAULT_SAMPLER, SamplerWire.SAMPLERS),
            scheduler = options.oneOf("scheduler", SamplerWire.DEFAULT_SCHEDULER, SamplerWire.SCHEDULERS),
            cfgScale = options.positiveDouble("cfg-scale", DEFAULT_CFG),
            batch = options.intIn("batch", DEFAULT_BATCH, BATCH_RANGE),
            engine = options["engine"],
            loraEntries = options.loraEntries(),
            loraDir = options["lora-dir"]?.takeIf { it.isNotBlank() }
                ?: ModelLibrary.resolve(null, env).resolve("lora").toString(),
        )
    }

    /**
     * `--lora` may be repeated or comma-separated. Both spellings go through
     * the copied [LoraSpec] tokeniser, so `a@0.5,b` and two `--lora` flags mean
     * the same thing. A multiplier that is not a number is caught here rather
     * than quietly becoming 1.0 in the tokeniser.
     */
    private fun Map<String, String>.loraEntries(): List<LoraSpec.Entry> {
        val raw = this["lora"]?.trim() ?: return emptyList()
        if (raw.isEmpty()) throw UsageException("option '--lora' must not be empty")
        for (piece in raw.split(',')) {
            val entry = piece.trim()
            val at = entry.lastIndexOf('@')
            if (at < 0) continue
            val text = entry.substring(at + 1).trim()
            val number = text.toDoubleOrNull()
                ?: throw UsageException("option '--lora' needs a numeric multiplier, got '$text'")
            if (!number.isFinite()) {
                throw UsageException("option '--lora' needs a finite multiplier, got '$text'")
            }
        }
        return LoraSpec.parse(raw)
    }

    private fun parseOptions(args: List<String>): Map<String, String> {
        val options = LinkedHashMap<String, String>()
        fun put(name: String, value: String) {
            // Every other repeated flag is last-wins; --lora is a list, so
            // joining with the same comma the mobile LoraSpec uses keeps one
            // tokeniser for both spellings.
            options[name] = if (name == "lora" && options.containsKey(name)) {
                "${options[name]},$value"
            } else {
                value
            }
        }
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
                put(name, body.substring(equals + 1))
                i += 1
            } else {
                requireKnown(body)
                if (i + 1 >= args.size) {
                    throw UsageException("option '--$body' needs a value")
                }
                put(body, args[i + 1])
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

    private fun Map<String, String>.intIn(name: String, default: Int, range: IntRange): Int {
        val raw = this[name] ?: return default
        val value = raw.toIntOrNull()
            ?: throw UsageException("option '--$name' needs an integer, got '$raw'")
        if (value !in range) {
            throw UsageException("option '--$name' must be between ${range.first} and ${range.last}, got $value")
        }
        return value
    }

    private fun Map<String, String>.size(name: String, default: Int): Int {
        val raw = this[name] ?: return default
        val value = raw.toIntOrNull()
            ?: throw UsageException("option '--$name' needs an integer, got '$raw'")
        if (value <= 0 || value % SIZE_MULTIPLE != 0) {
            throw UsageException(
                "option '--$name' must be a positive multiple of $SIZE_MULTIPLE, got $value",
            )
        }
        return value
    }

    private fun Map<String, String>.positiveDouble(name: String, default: Double): Double {
        val raw = this[name] ?: return default
        val value = raw.toDoubleOrNull()
            ?: throw UsageException("option '--$name' needs a number, got '$raw'")
        if (!value.isFinite() || value <= 0.0) {
            throw UsageException("option '--$name' must be positive, got $raw")
        }
        return value
    }

    /**
     * ⚠ The accepted values come from [SamplerWire], which is the copied mobile
     * table narrowed to the engine's own arrays -- never a list written here. A
     * second list is how a command line ends up offering a name the engine
     * quietly treats as something else.
     */
    private fun Map<String, String>.oneOf(name: String, default: String, allowed: List<String>): String {
        val value = this[name] ?: return default
        if (value !in allowed) {
            throw UsageException(
                "option '--$name' must be one of [${allowed.joinToString(", ")}], got '$value'",
            )
        }
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
