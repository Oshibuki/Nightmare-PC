package com.abrah.nightmare.desktop.cli

import com.abrah.nightmare.desktop.core.ModelLibrary
import com.abrah.nightmare.desktop.core.ProductIdentity
import com.abrah.nightmare.desktop.core.SamplerWire

/**
 * The command line surface: identity, usage, and the `txt2img` command.
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
            Img2Img.NAME -> Img2Img.run(args.drop(1), out, err, env)
            Models.NAME -> Models.run(args.drop(1), out, err, env)
            Serve.NAME -> Serve.run(args.drop(1), out, err, env)
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
        |  ${ProductIdentity.NAME} img2img --model <path> --init-img <path> --out <path> [options]
        |  ${ProductIdentity.NAME} txt2img --diffusion-model <gguf> --llm <gguf> --vae <file> --prompt <text> --out <path> [options]
        |  ${ProductIdentity.NAME} models list [--dir <path>]
        |  ${ProductIdentity.NAME} serve --model <path> [--port <1-65535>]
        |
        |Options:
        |  -h, --help     Show this help and exit.
        |      --version  Print the product version and exit.
        |
        |Commands:
        |  txt2img        Generate images from a prompt with the vendored engine.
        |  img2img        Rewrite an existing PNG with a prompt and a denoising strength.
        |  models list    List the models in the local library, with their format.
        |  serve          Run the engine's A1111-compatible local HTTP API.
        |
        |txt2img options:
        |      --model <path>              Model file to load. A single-file
        |                                  .safetensors checkpoint; its header is checked
        |                                  before the engine starts. Required unless the
        |                                  multi-file options below are used.
        |      --diffusion-model <path>    Standalone diffusion model of a multi-file
        |                                  DiT/GGUF pipeline. Requires --llm and --vae,
        |                                  and is mutually exclusive with --model.
        |      --llm <path>                Text encoder of that pipeline (required with
        |                                  --diffusion-model).
        |      --vae <path>                VAE decoder of that pipeline (required with
        |                                  --diffusion-model); .safetensors or .gguf.
        |      --llm_vision <path>         Vision encoder of that pipeline, e.g. the
        |                                  mmproj of Qwen Image (optional).
        |      --prompt <text>             Prompt to render (required).
        |      --out <path>                PNG file to write (required). With
        |                                  --batch, the first image is written
        |                                  here and the rest beside it.
        |      --negative-prompt <text>    Negative prompt (default: empty).
        |      --width <int>               Image width in pixels (default: 512);
        |                                  a positive multiple of 8.
        |      --height <int>              Image height in pixels (default: 512);
        |                                  a positive multiple of 8.
        |      --steps <int>               Sampling steps, 1-150 (default: 20).
        |      --sampler <name>            Sampling method (default: euler). One of:
        |                                  ${SamplerWire.SAMPLERS.joinToString(", ")}.
        |      --scheduler <name>          Sigma schedule (default: discrete). One of:
        |                                  ${SamplerWire.SCHEDULERS.joinToString(", ")}.
        |      --cfg-scale <number>        Classifier-free guidance scale, > 0
        |                                  (default: 7).
        |      --seed <int>                RNG seed (default: 42). -1 draws a random
        |                                  seed, which is printed and recorded.
        |      --batch <int>               Images to generate, 1-16 (default: 1).
        |                                  They are generated one at a time with the
        |                                  seed incrementing from --seed, and named
        |                                  <stem>-<i>.png after the first, which
        |                                  keeps the --out path.
        |      --lora <file>[@mult]        SD LoRA to apply; the multiplier defaults
        |                                  to 1 and may be negative. Repeat the flag
        |                                  or comma-separate several: --lora a@0.6,b.
        |      --lora-dir <path>           Folder holding the LoRA weights. Defaults
        |                                  to ${'$'}NIGHTMARE_MODELS/lora, then
        |                                  ~/${ModelLibrary.DEFAULT_SUBPATH}/lora.
        |      --engine <path>             sd-cli binary to run. Defaults to
        |                                  ${'$'}NIGHTMARE_SD_BIN, then ${Txt2Img.DEFAULT_ENGINE}.
        |
        |img2img options:
        |      --model <path>              Model file to load (required, single-file
        |                                  .safetensors; its header is checked).
        |      --init-img <path>           Source PNG to rewrite (required). Its width
        |                                  and height must be positive multiples of 8,
        |                                  and default the output size when not given.
        |      --out <path>                PNG file to write (required).
        |      --strength <number>         Denoising strength in [0, 1] (default: 0.75).
        |                                  0 keeps the source, 1 fully re-generates it.
        |      --prompt <text>             Prompt to render (default: empty).
        |      --negative-prompt <text>    Negative prompt (default: empty).
        |      --width <int>               Output width, positive multiple of 8.
        |      --height <int>              Output height, positive multiple of 8.
        |      --steps <int>               Sampling steps, 1-150 (default: 20).
        |      --sampler <name>            Sampling method (default: euler).
        |      --scheduler <name>          Sigma schedule (default: discrete).
        |      --cfg-scale <number>        Classifier-free guidance scale, > 0 (default: 7).
        |      --seed <int>                RNG seed (default: 42). -1 draws a random seed.
        |      --engine <path>             sd-cli binary to run (same defaults as txt2img).
        |
        |models list options:
        |      --dir <path>                Directory to scan. Defaults to
        |                                  ${'$'}${ModelLibrary.ENV_MODELS}, then ~/${ModelLibrary.DEFAULT_SUBPATH}.
        |
        |serve options:
        |      --model <path>              Model file to load (required).
        |      --port <1-65535>            TCP port (default: ${Serve.DEFAULT_PORT}). The
        |                                  address is always 127.0.0.1; there is
        |                                  no flag to bind another interface.
        |      --engine <path>             sd-server binary to run. Defaults to
        |                                  ${'$'}${Serve.ENV_ENGINE}, then ${Serve.DEFAULT_ENGINE}.
        |
        |The HTTP service is off by default and starts only when the `serve`
        |command is given. It binds 127.0.0.1 only, so it is never reachable
        |from the LAN; /sdapi/v1/* comes from the engine itself. Real A1111
        |client compatibility is not claimed here.
        |
        |Progress goes to stdout: one "[i/N] seed=<seed> -> <path>" line per image,
        |then the engine's own output as it arrives. Each PNG carries an
        |A1111-style "parameters" chunk (prompt, negative prompt, steps, sampler,
        |schedule, CFG scale, seed, size, model, LoRAs) so a batch can be
        |reproduced later. Prompt-embedded <lora:name:mult> tags are read too.
        |
        |Exit codes:
        |  0  the request was served (an empty model library included)
        |  1  it failed (missing model or library, engine error, unusable output)
        |  2  the command line was wrong
        |
        |Model weights are not committed; see desktop/docs/models.md.
        |""".trimMargin()
}
