package com.abrah.nightmare.desktop.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Seam: [Cli.run] plus the files it leaves behind -- the same seam as
 * [Txt2ImgTest], widened to the Phase 1 parameter surface. Nothing here knows
 * how the CLI is put together: the "engine" is a few lines of `/bin/sh` that
 * records its arguments and copies a real PNG into place, so every assertion is
 * about what a user observes (exit code, the two streams, the engine's command
 * line, the PNG and its metadata).
 */
class Txt2ImgFullCliTest {

    private val out = StringBuilder()
    private val err = StringBuilder()

    private lateinit var dir: File
    private lateinit var model: File
    private lateinit var argvFile: File
    private lateinit var outPng: File

    @Before
    fun setUp() {
        assumeTrue("/bin/sh is required by the fake engine", File("/bin/sh").exists())
        dir = Files.createTempDirectory("nm-cli-full-test").toFile()
        model = safetensors("model.safetensors")
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

    // ---- the command line ---------------------------------------------------

    private fun run(vararg args: String): Int = Cli.run(args.toList(), out, err)

    private fun txt2img(engine: File, vararg extra: String): Int = run(
        "txt2img",
        "--model", model.absolutePath,
        "--prompt", "a red fox",
        "--out", outPng.absolutePath,
        "--engine", engine.absolutePath,
        *extra,
    )

    /** A script that records each invocation's arguments and copies [png] to whatever `-o` names. */
    private fun fakeEngine(png: File): File {
        val script = File(dir, "sd-cli")
        script.writeText(
            """
            #!/bin/sh
            {
              for a in "${'$'}@"; do printf '%s\n' "${'$'}a"; done
              printf '###\n'
            } >> '${argvFile.absolutePath}'
            echo 'fake engine: generating'
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

    /** One recorded engine invocation, as the engine received it. */
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

    private fun parameters(file: File): String =
        TestPng.parameters(file) ?: throw AssertionError("no 'parameters' chunk in ${file.name}")

    // ---- parameters reach the engine ---------------------------------------

    @Test
    fun everyParameterReachesTheEngineInTheDocumentedOrder() {
        val engine = fakeEngine(stubPng(640, 448))

        val code = txt2img(
            engine,
            "--negative-prompt", "blurry, low quality",
            "--width", "640", "--height", "448",
            "--steps", "8",
            "--sampler", "euler_a",
            "--scheduler", "karras",
            "--cfg-scale", "5.5",
            "--seed", "1234",
        )

        assertEquals("stderr: $err", 0, code)
        assertEquals(
            listOf(
                "-m", model.absolutePath,
                "-p", "a red fox",
                "-n", "blurry, low quality",
                "-W", "640",
                "-H", "448",
                "--steps", "8",
                "--seed", "1234",
                "--sampling-method", "euler_a",
                "--scheduler", "karras",
                "--cfg-scale", "5.5",
                "-o", outPng.absolutePath,
            ),
            invocations().single(),
        )
    }

    @Test
    fun unspecifiedParametersFallBackToTheDocumentedDefaults() {
        val engine = fakeEngine(stubPng(512, 512))

        val code = txt2img(engine)

        assertEquals("stderr: $err", 0, code)
        assertEquals("one engine run for the default batch of 1", 1, invocations().size)
        val argv = invocations().single()
        assertEquals("512", argValue(argv, "-W"))
        assertEquals("512", argValue(argv, "-H"))
        assertEquals("20", argValue(argv, "--steps"))
        assertEquals("42", argValue(argv, "--seed"))
        assertEquals("euler", argValue(argv, "--sampling-method"))
        assertEquals("discrete", argValue(argv, "--scheduler"))
        assertEquals("7", argValue(argv, "--cfg-scale"))
        assertEquals("", argValue(argv, "-n"))
    }

    @Test
    fun everySamplerAndSchedulerTheEngineKnowsIsAccepted() {
        val engine = fakeEngine(stubPng(512, 512))
        // The vocabulary is the engine's own: these are sample_method_to_str[] and
        // scheduler_to_str[] values, read from the vendored source. Anything else
        // is refused at run time by str_to_sample_method / str_to_scheduler.
        val samplers = listOf(
            "euler", "euler_a", "heun", "dpm++2m", "dpm++2m_sde", "er_sde",
            "res_multistep", "ipndm", "lcm",
        )
        val schedulers = listOf(
            "discrete", "karras", "exponential", "sgm_uniform", "bong_tangent", "simple", "beta",
        )
        for (sampler in samplers) {
            out.setLength(0); err.setLength(0)
            assertEquals("--sampler $sampler: $err", 0, txt2img(engine, "--sampler", sampler))
            assertEquals(sampler, argValue(invocations().last(), "--sampling-method"))
        }
        for (scheduler in schedulers) {
            out.setLength(0); err.setLength(0)
            assertEquals("--scheduler $scheduler: $err", 0, txt2img(engine, "--scheduler", scheduler))
            assertEquals(scheduler, argValue(invocations().last(), "--scheduler"))
        }
    }

    // ---- every illegal value is a usage error, before the engine runs ------

    @Test
    fun everyIllegalParameterIsAUsageErrorAndTheEngineNeverRuns() {
        val engine = fakeEngine(stubPng(512, 512))
        val cases = listOf(
            listOf("--width", "0") to "--width",
            listOf("--width", "-8") to "--width",
            listOf("--width", "100") to "--width",
            listOf("--width", "big") to "--width",
            listOf("--height", "0") to "--height",
            listOf("--height", "12") to "--height",
            listOf("--steps", "0") to "--steps",
            listOf("--steps", "151") to "--steps",
            listOf("--steps", "-1") to "--steps",
            listOf("--steps", "many") to "--steps",
            listOf("--cfg-scale", "0") to "--cfg-scale",
            listOf("--cfg-scale", "-1.5") to "--cfg-scale",
            listOf("--cfg-scale", "high") to "--cfg-scale",
            listOf("--batch", "0") to "--batch",
            listOf("--batch", "17") to "--batch",
            listOf("--batch", "two") to "--batch",
            listOf("--seed", "abc") to "--seed",
            // the mobile backend's ids are not the engine's
            listOf("--sampler", "dpm") to "--sampler",
            listOf("--sampler", "h3_spectral") to "--sampler",
            listOf("--sampler", "euler_a_karras") to "--sampler",
            listOf("--sampler", "Euler") to "--sampler",
            // the app's suffix spelling is not the engine's word
            listOf("--scheduler", "_karras") to "--scheduler",
            listOf("--scheduler", "normal") to "--scheduler",
            listOf("--scheduler", "linear_quadratic") to "--scheduler",
            listOf("--scheduler", "unipm") to "--scheduler",
        )

        for ((args, option) in cases) {
            out.setLength(0); err.setLength(0)
            val code = txt2img(engine, *args.toTypedArray())
            assertEquals("$args should be a usage error, stderr: $err", 2, code)
            assertTrue("$args must name $option on stderr, got: $err", err.contains(option))
            assertTrue("$args must not print to stdout, got: $out", out.isEmpty())
        }
        assertTrue("the engine must never run on a bad command line: $argvFile", invocations().isEmpty())
    }

    @Test
    fun aUsageErrorListsTheValuesThatWouldHaveWorked() {
        val engine = fakeEngine(stubPng(512, 512))

        val code = txt2img(engine, "--sampler", "dpm")

        assertEquals("stderr: $err", 2, code)
        assertTrue("stderr should name the accepted values: $err", err.contains("euler_a"))
        assertTrue("stderr should name the accepted values: $err", err.contains("lcm"))
    }

    // ---- A1111-style metadata ----------------------------------------------

    @Test
    fun thePngCarriesTheParametersAsATextChunk() {
        val engine = fakeEngine(stubPng(640, 448))

        val code = txt2img(
            engine,
            "--negative-prompt", "blurry, low quality",
            "--width", "640", "--height", "448",
            "--steps", "8",
            "--sampler", "euler_a",
            "--scheduler", "karras",
            "--cfg-scale", "5.5",
            "--seed", "1234",
        )

        assertEquals("stderr: $err", 0, code)
        val chunks = TestPng.chunks(outPng)
        assertEquals("the metadata chunk must sit before IEND", "IEND", chunks.last().first)
        assertTrue("expected a tEXt chunk: ${chunks.map { it.first }}", chunks.any { it.first == "tEXt" })
        assertEquals(
            """
            |a red fox
            |Negative prompt: blurry, low quality
            |Steps: 8, Sampler: Euler A, Schedule type: Karras, CFG scale: 5.5, Seed: 1234, Size: 640x448, Model: model.safetensors
            """.trimMargin(),
            parameters(outPng),
        )
        // ⚠ And the file is still a PNG an ordinary reader can open.
        val image = ImageIO.read(outPng) ?: throw AssertionError("ImageIO could not decode $outPng")
        assertEquals(640, image.width)
        assertEquals(448, image.height)
    }

    @Test
    fun anEmptyNegativePromptLeavesTheLineOut() {
        val engine = fakeEngine(stubPng(512, 512))

        assertEquals("stderr: $err", 0, txt2img(engine))

        assertEquals(
            """
            |a red fox
            |Steps: 20, Sampler: Euler, Schedule type: Normal, CFG scale: 7, Seed: 42, Size: 512x512, Model: model.safetensors
            """.trimMargin(),
            parameters(outPng),
        )
    }

    /**
     * ⚠⚠ The real engine writes its OWN `parameters` chunk, so "append" would
     * leave two chunks under one keyword holding two different records -- and
     * readers disagree about which one wins. Ours must replace it, leaving
     * exactly one, and it must be ours.
     */
    @Test
    fun anEngineWrittenParametersChunkIsReplacedNotDuplicated() {
        val engine = fakeEngine(
            TestPng.fileWithParameters(
                dir, 512, 512,
                "a red fox\nSteps: 20, CFG scale: 7.000000, Seed: 42, Sampler: euler, SDCPP: {\"schema\":\"sdcpp.image.params/v1\"}",
            ),
        )

        val code = txt2img(engine, "--steps", "8", "--sampler", "euler_a")

        assertEquals("stderr: $err", 0, code)
        val texts = TestPng.parametersChunks(outPng)
        assertEquals("exactly one parameters chunk, got: $texts", 1, texts.size)
        assertTrue(
            "the surviving chunk must be the CLI's: ${texts.single()}",
            texts.single().contains("Sampler: Euler A") && texts.single().contains("Steps: 8"),
        )
        assertTrue("the engine's stale record must be gone", !texts.single().contains("CFG scale: 7.000000"))
        assertTrue("ImageIO must still decode it", ImageIO.read(outPng) != null)
    }

    @Test
    fun aPromptOutsideLatin1SurvivesTheMetadataRoundTrip() {
        val engine = fakeEngine(stubPng(512, 512))

        val code = run(
            "txt2img",
            "--model", model.absolutePath,
            "--prompt", "秋の森の赤い狐",
            "--out", outPng.absolutePath,
            "--engine", engine.absolutePath,
        )

        assertEquals("stderr: $err", 0, code)
        val text = parameters(outPng)
        assertTrue("the prompt must survive verbatim: $text", text.startsWith("秋の森の赤い狐\n"))
        assertTrue("ImageIO must still decode it", ImageIO.read(outPng) != null)
    }

    // ---- batch -------------------------------------------------------------

    @Test
    fun aBatchWritesOneNumberedPngPerImageWithAnIncrementingSeed() {
        val engine = fakeEngine(stubPng(512, 512))

        val code = txt2img(engine, "--batch", "3", "--seed", "100")

        assertEquals("stderr: $err", 0, code)
        val first = outPng
        val second = File(dir, "out-2.png")
        val third = File(dir, "out-3.png")
        assertTrue("the first image is --out: ${dir.list()?.toList()}", first.isFile)
        assertTrue("the second image is out-2.png: ${dir.list()?.toList()}", second.isFile)
        assertTrue("the third image is out-3.png: ${dir.list()?.toList()}", third.isFile)

        assertEquals("one engine run per image", 3, invocations().size)
        assertEquals(listOf("100", "101", "102"), invocations().map { argValue(it, "--seed") })
        assertEquals(
            listOf(first.absolutePath, second.absolutePath, third.absolutePath),
            invocations().map { argValue(it, "-o") },
        )

        for ((file, seed) in listOf(first to "100", second to "101", third to "102")) {
            assertTrue("${file.name} should record seed $seed", parameters(file).contains("Seed: $seed"))
        }
    }

    @Test
    fun aBatchPrintsOneProgressLinePerImage() {
        val engine = fakeEngine(stubPng(512, 512))

        val code = txt2img(engine, "--batch", "3", "--seed", "7")

        assertEquals("stderr: $err", 0, code)
        val lines = out.lines()
        for (i in 1..3) {
            assertTrue(
                "stdout should carry a '[$i/3] seed=...' line for image $i, got: $out",
                lines.any { it.startsWith("[$i/3] seed=") },
            )
        }
        assertTrue("the seed of image 2 should be visible: $out", out.contains("seed=8"))
        assertTrue("the engine's own output must still be forwarded: $out", out.contains("fake engine: generating"))
    }

    @Test
    fun aBatchStopsAtTheFirstEngineFailure() {
        val script = File(dir, "failing")
        script.writeText("#!/bin/sh\necho 'out of memory' >&2\nexit 4\n")
        assertTrue(script.setExecutable(true))

        val code = txt2img(script, "--batch", "3")

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr should name the exit code: $err", err.contains("4"))
        assertTrue("no image may be claimed: $err", !outPng.exists())
    }

    @Test
    fun aSeedOfMinusOneDrawsARandomOneAndReportsIt() {
        val engine = fakeEngine(stubPng(512, 512))

        assertEquals("stderr: $err", 0, txt2img(engine, "--seed", "-1"))

        val seed = argValue(invocations().single(), "--seed")!!.toLong()
        assertTrue("a drawn seed must not be negative, got $seed", seed >= 0)
        assertTrue("the metadata must record the seed that was used", parameters(outPng).contains("Seed: $seed"))
        assertNotEquals("a drawn seed should not be the default", 42L, seed)
    }

    @Test
    fun aBatchOfRandomSeedsStillIncrements() {
        val engine = fakeEngine(stubPng(512, 512))

        assertEquals("stderr: $err", 0, txt2img(engine, "--seed", "-1", "--batch", "2"))

        val seeds = invocations().map { argValue(it, "--seed")!!.toLong() }
        assertEquals(2, seeds.size)
        assertTrue("the base seed must be drawn, got ${seeds[0]}", seeds[0] >= 0)
        assertEquals("batch seeds increment from the drawn one", seeds[0] + 1, seeds[1])
    }

    // ---- failures keep their contract --------------------------------------

    @Test
    fun aWrongSizedPngIsRejectedInABatchToo() {
        val engine = fakeEngine(stubPng(64, 64))

        val code = txt2img(engine, "--width", "512", "--height", "512", "--batch", "2")

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr should report the actual size: $err", err.contains("64x64"))
    }
}
