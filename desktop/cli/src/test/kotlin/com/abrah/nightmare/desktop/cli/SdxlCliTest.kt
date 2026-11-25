package com.abrah.nightmare.desktop.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * Seam: [Cli.run] plus the arguments its fake engine records -- the CLI
 * end-to-end seam shared with [Txt2ImgFullCliTest].
 *
 * SDXL is native in the pinned engine: no patch is a prerequisite (see
 * desktop/engine/SDXL.md). What the CLI owns is the SDXL weight and VAE-tiling
 * vocabulary: the knobs must reach `sd-cli` verbatim, and a bad value must be
 * refused before a 6.9 GB checkpoint is loaded. Nothing here inspects how the
 * parser is built; every assertion is about what a caller observes.
 */
class SdxlCliTest {

    private val out = StringBuilder()
    private val err = StringBuilder()

    private lateinit var dir: File
    private lateinit var model: File
    private lateinit var vae: File
    private lateinit var argvFile: File
    private lateinit var outPng: File

    @Before
    fun setUp() {
        assumeTrue("/bin/sh is required by the fake engine", File("/bin/sh").exists())
        dir = Files.createTempDirectory("nm-sdxl-cli-test").toFile()
        model = safetensors("sdxl-base.safetensors")
        vae = safetensors("sdxl-vae.safetensors")
        argvFile = File(dir, "argv.txt")
        outPng = File(dir, "out.png")
    }

    /** A real safetensors header: the CLI checks bytes before starting the engine. */
    private fun safetensors(name: String): File {
        val json = """{"w":{"dtype":"F16","shape":[2],"data_offsets":[0,4]}}"""
        val header = json.toByteArray(Charsets.UTF_8)
        val bytes = ByteBuffer.allocate(8 + header.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(header.size.toLong())
            .put(header)
            .array()
        return File(dir, name).apply { writeBytes(bytes) }
    }

    private fun run(vararg args: String): Int = Cli.run(args.toList(), out, err)

    private fun txt2img(engine: File, vararg extra: String): Int = run(
        "txt2img",
        "--model", model.absolutePath,
        "--prompt", "a red fox at 1024",
        "--out", outPng.absolutePath,
        "--engine", engine.absolutePath,
        *extra,
    )

    /** Records every argument on its own line, then copies [png] to whatever `-o` names. */
    private fun fakeEngine(png: File): File {
        val script = File(dir, "sd-cli")
        script.writeText(
            """
            #!/bin/sh
            {
              for a in "${'$'}@"; do printf '%s\n' "${'$'}a"; done
              printf '###\n'
            } >> '${argvFile.absolutePath}'
            out=''
            prev=''
            for a in "${'$'}@"; do
              if [ "${'$'}prev" = '-o' ]; then out="${'$'}a"; fi
              prev="${'$'}a"
            done
            cp '${png.absolutePath}' "${'$'}out"
            """.trimIndent() + "\n",
        )
        assertTrue("fake engine must be executable", script.setExecutable(true))
        return script
    }

    private fun stubPng(width: Int, height: Int): File = TestPng.file(dir, width, height)

    private fun invocations(): List<List<String>> {
        if (!argvFile.isFile) return emptyList()
        val blocks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        for (line in argvFile.readLines()) {
            if (line == "###") {
                blocks += current
                current = mutableListOf()
            } else {
                current += line
            }
        }
        return blocks
    }

    private fun argValue(argv: List<String>, flag: String): String? {
        val i = argv.indexOf(flag)
        return if (i >= 0 && i + 1 < argv.size) argv[i + 1] else null
    }

    // ---- SDXL weights and VAE tiling reach the engine ----------------------

    @Test
    fun sdxlWeightsAt1024WithTilingReachTheEngine() {
        val engine = fakeEngine(stubPng(1024, 1024))

        val code = txt2img(
            engine,
            "--width", "1024", "--height", "1024",
            "--vae", vae.absolutePath,
            "--vae-tiling",
        )

        assertEquals("stderr: $err", 0, code)
        val argv = invocations().single()
        assertEquals(model.absolutePath, argValue(argv, "-m"))
        assertEquals("1024", argValue(argv, "-W"))
        assertEquals("1024", argValue(argv, "-H"))
        assertEquals(vae.absolutePath, argValue(argv, "--vae"))
        assertTrue("a bare --vae-tiling must be forwarded: $argv", "--vae-tiling" in argv)
        // The tiling switch is a flag, not a value-taking option: the next
        // engine argument must not be mistaken for its value.
        assertEquals(outPng.absolutePath, argValue(argv, "-o"))
    }

    @Test
    fun vaeTilingCanBeExplicitlySwitchedOff() {
        val engine = fakeEngine(stubPng(512, 512))

        val code = txt2img(engine, "--vae-tiling=false")

        assertEquals("stderr: $err", 0, code)
        assertFalse("--vae-tiling" in invocations().single())
    }

    @Test
    fun vaeTileSizeAndOverlapReachTheEngineVerbatim() {
        val engine = fakeEngine(stubPng(512, 512))

        val code = txt2img(
            engine,
            "--vae-tiling",
            "--vae-tile-size", "64x96",
            "--vae-tile-overlap", "0.25",
        )

        assertEquals("stderr: $err", 0, code)
        val argv = invocations().single()
        assertEquals("64x96", argValue(argv, "--vae-tile-size"))
        assertEquals("0.25", argValue(argv, "--vae-tile-overlap"))
    }

    @Test
    fun aDefaultRunSendsNoVaeFlags() {
        val engine = fakeEngine(stubPng(512, 512))

        assertEquals("stderr: $err", 0, txt2img(engine))

        val argv = invocations().single()
        assertTrue(
            "SD1.5 must not gain SDXL-only flags: $argv",
            argv.none { it == "--vae" || it.startsWith("--vae-") },
        )
    }

    // ---- bad values cost a second, not a model load ------------------------

    @Test
    fun illegalVaeOptionsAreUsageErrorsAndTheEngineNeverRuns() {
        val engine = fakeEngine(stubPng(512, 512))
        val cases = listOf(
            listOf("--vae-tile-size", "64") to "--vae-tile-size",
            listOf("--vae-tile-size", "0x32") to "--vae-tile-size",
            listOf("--vae-tile-size", "axb") to "--vae-tile-size",
            listOf("--vae-tile-overlap", "0") to "--vae-tile-overlap",
            listOf("--vae-tile-overlap", "1.5") to "--vae-tile-overlap",
            listOf("--vae-tile-overlap", "many") to "--vae-tile-overlap",
            listOf("--vae-tiling=maybe") to "--vae-tiling",
            listOf("--vae", vae.absolutePath + ".txt") to "--vae",
        )

        for ((args, option) in cases) {
            out.setLength(0); err.setLength(0)
            val code = txt2img(engine, *args.toTypedArray())
            assertEquals("$args should be a usage error, stderr: $err", 2, code)
            assertTrue("$args must name $option on stderr: $err", err.contains(option))
            assertTrue("$args must not print to stdout: $out", out.isEmpty())
        }
        assertTrue("the engine must never run on a bad command line: $argvFile", invocations().isEmpty())
    }

    @Test
    fun aMissingVaeFileFailsBeforeTheEngineStarts() {
        val engine = fakeEngine(stubPng(512, 512))
        val missing = File(dir, "no-such-vae.safetensors")

        val code = txt2img(engine, "--vae", missing.absolutePath)

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr must name the missing VAE: $err", err.contains(missing.name))
        assertTrue("the engine must not run: $argvFile", invocations().isEmpty())
    }

    @Test
    fun helpDocumentsTheSdxlAndVaeOptions() {
        val code = run("--help")

        assertEquals("stderr: $err", 0, code)
        for (flag in listOf("--vae", "--vae-tiling", "--vae-tile-size", "--vae-tile-overlap")) {
            assertTrue("help must document $flag: $out", out.contains(flag))
        }
    }
}
