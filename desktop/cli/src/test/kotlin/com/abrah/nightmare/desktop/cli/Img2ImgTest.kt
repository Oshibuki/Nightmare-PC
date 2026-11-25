package com.abrah.nightmare.desktop.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Seam: [Cli.run] plus the files it leaves behind -- the same seam as the
 * txt2img tests, widened to img2img. Nothing here knows how the CLI is built:
 * the "engine" is a few lines of `/bin/sh` that records its arguments and
 * copies a PNG into place, so every assertion is about what a user observes --
 * the exit code, the two streams, the engine's command line, and the pixels of
 * the PNG on disk.
 */
class Img2ImgTest {

    private val out = StringBuilder()
    private val err = StringBuilder()

    private lateinit var dir: File
    private lateinit var model: File
    private lateinit var initPng: File
    private lateinit var argvFile: File
    private lateinit var outPng: File

    @Before
    fun setUp() {
        assumeTrue("/bin/sh is required by the fake engine", File("/bin/sh").exists())
        dir = Files.createTempDirectory("nm-img2img-test").toFile()
        model = safetensors("model.safetensors")
        initPng = patternedPng(File(dir, "init.png"), 64, 64, 30)
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

    /** A PNG with a deterministic, non-uniform pixel pattern, so a pixel diff is meaningful. */
    private fun patternedPng(file: File, width: Int, height: Int, base: Int): File {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = (base + x) and 0xFF
                val g = (base + y) and 0xFF
                val b = (base + ((x * y) and 0xFF)) and 0xFF
                image.setRGB(x, y, (r shl 16) or (g shl 8) or b)
            }
        }
        ImageIO.write(image, "png", file)
        return file
    }

    private fun run(vararg args: String): Int = Cli.run(args.toList(), out, err)

    private fun img2img(engine: File, vararg extra: String): Int = run(
        "img2img",
        "--model", model.absolutePath,
        "--init-img", initPng.absolutePath,
        "--out", outPng.absolutePath,
        "--prompt", "a red fox",
        "--engine", engine.absolutePath,
        *extra,
    )

    /** A script that records each invocation's arguments and copies [png] to whatever `-o` names. */
    private fun fakeEngine(png: File, name: String = "sd-cli"): File {
        val script = File(dir, name)
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

    /**
     * A fake engine that mimics the one semantic the pixels depend on: strength
     * 0 copies the source image, any other strength writes a different image.
     * If the CLI drops either `--init-img` or `--strength`, one of the two
     * assertions below fails -- no human has to look at the pictures.
     */
    private fun strengthEngine(changed: File): File {
        val script = File(dir, "strength-engine")
        script.writeText(
            """
            #!/bin/sh
            {
              for a in "${'$'}@"; do printf '%s\n' "${'$'}a"; done
              printf '###\n'
            } >> '${argvFile.absolutePath}'
            strength=''
            init=''
            out=''
            prev=''
            for a in "${'$'}@"; do
              case "${'$'}prev" in
                --strength) strength="${'$'}a" ;;
                --init-img) init="${'$'}a" ;;
                -o) out="${'$'}a" ;;
              esac
              prev="${'$'}a"
            done
            if [ "${'$'}strength" = '0' ] || [ "${'$'}strength" = '0.0' ]; then
              cp "${'$'}init" "${'$'}out"
            else
              cp '${changed.absolutePath}' "${'$'}out"
            fi
            """.trimIndent() + "\n",
        )
        assertTrue("fake engine must be executable", script.setExecutable(true))
        return script
    }

    /** The 24-bit RGB pixels of an image, independent of any code under test. */
    private fun pixels(file: File): Array<IntArray> {
        val image = ImageIO.read(file) ?: throw AssertionError("ImageIO could not decode $file")
        return Array(image.height) { y -> IntArray(image.width) { x -> image.getRGB(x, y) and 0xFFFFFF } }
    }

    /** Mean absolute per-channel difference in [0, 255]; 0 means identical pixels. */
    private fun meanAbsDiff(a: File, b: File): Double {
        val left = pixels(a)
        val right = pixels(b)
        assertEquals("the two images must have the same height", left.size, right.size)
        var sum = 0L
        var channels = 0L
        for (y in left.indices) {
            assertEquals("the two images must have the same width", left[y].size, right[y].size)
            for (x in left[y].indices) {
                val l = left[y][x]
                val r = right[y][x]
                sum += kotlin.math.abs((l shr 16 and 0xFF) - (r shr 16 and 0xFF)).toLong()
                sum += kotlin.math.abs((l shr 8 and 0xFF) - (r shr 8 and 0xFF)).toLong()
                sum += kotlin.math.abs((l and 0xFF) - (r and 0xFF)).toLong()
                channels += 3
            }
        }
        return sum.toDouble() / channels
    }

    private fun parameters(file: File): String =
        TestPng.parameters(file) ?: throw AssertionError("no 'parameters' chunk in ${file.name}")

    /** A script that does whatever [body] says and exits [exitCode]. */
    private fun script(name: String, exitCode: Int = 0, body: String): File {
        val file = File(dir, name)
        file.writeText("#!/bin/sh\n$body\nexit $exitCode\n")
        assertTrue("fake engine must be executable", file.setExecutable(true))
        return file
    }

    // ---- the command reaches the engine ------------------------------------

    @Test
    fun img2imgPassesInitImageAndStrengthToTheEngineAndWritesPng() {
        val engine = fakeEngine(TestPng.file(dir, 64, 64, "generated.png"))

        val code = img2img(engine, "--strength", "0.5", "--seed", "7")

        assertEquals("stderr: $err", 0, code)
        assertTrue("a success must not write to stderr: $err", err.isEmpty())
        val argv = invocations().single()
        assertEquals(initPng.absolutePath, argValue(argv, "--init-img"))
        assertEquals("0.5", argValue(argv, "--strength"))
        assertEquals("7", argValue(argv, "--seed"))
        val image = ImageIO.read(outPng) ?: throw AssertionError("ImageIO could not decode $outPng")
        assertEquals(64, image.width)
        assertEquals(64, image.height)
    }

    @Test
    fun everyParameterReachesTheEngineInTheDocumentedOrder() {
        val engine = fakeEngine(TestPng.file(dir, 128, 64, "generated.png"))

        val code = img2img(
            engine,
            "--negative-prompt", "blurry",
            "--width", "128", "--height", "64",
            "--steps", "8", "--sampler", "euler_a", "--scheduler", "karras",
            "--cfg-scale", "5.5", "--strength", "0.4", "--seed", "1234",
        )

        assertEquals("stderr: $err", 0, code)
        assertEquals(
            listOf(
                "-m", model.absolutePath,
                "-p", "a red fox",
                "-n", "blurry",
                "--init-img", initPng.absolutePath,
                "--strength", "0.4",
                "-W", "128",
                "-H", "64",
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

    // ---- fixed seed: strength 0 == source, strength 1 differs --------------

    @Test
    fun fixedSeedStrengthZeroMatchesTheSourceAndStrengthOneDiffersBeyondTheThreshold() {
        val changed = patternedPng(File(dir, "changed.png"), 64, 64, 200)
        val engine = strengthEngine(changed)

        val zeroCode = img2img(engine, "--strength", "0", "--seed", "42")
        assertEquals("strength 0: stderr: $err", 0, zeroCode)
        val zero = File(dir, "zero.png").apply { writeBytes(outPng.readBytes()) }

        out.setLength(0); err.setLength(0)
        val oneCode = img2img(engine, "--strength", "1", "--seed", "42")
        assertEquals("strength 1: stderr: $err", 0, oneCode)
        val one = File(dir, "one.png").apply { writeBytes(outPng.readBytes()) }

        assertEquals("strength 0 must reproduce the source pixel for pixel", 0.0, meanAbsDiff(initPng, zero), 0.0)
        val diff = meanAbsDiff(initPng, one)
        assertTrue("strength 1 must change the pixels substantially, got mean diff $diff", diff > 20.0)

        val runs = invocations()
        assertEquals("one engine run per strength", 2, runs.size)
        assertEquals("0", argValue(runs[0], "--strength"))
        assertEquals("1", argValue(runs[1], "--strength"))
        assertEquals("the seed must be fixed for both runs", "42", argValue(runs[0], "--seed"))
        assertEquals("the seed must be fixed for both runs", "42", argValue(runs[1], "--seed"))
    }

    // ---- bad input is a clear, non-zero exit -------------------------------

    @Test
    fun anInitImageThatIsNotAPngFailsBeforeTheEngineStarts() {
        val engine = fakeEngine(TestPng.file(dir, 64, 64, "generated.png"))
        val bad = File(dir, "not-an-image.txt").apply { writeText("hello") }

        val code = run(
            "img2img",
            "--model", model.absolutePath,
            "--init-img", bad.absolutePath,
            "--out", outPng.absolutePath,
            "--engine", engine.absolutePath,
        )

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr must name the file: $err", err.contains(bad.absolutePath))
        assertTrue("stderr must say it is not a PNG: $err", err.contains("not a PNG"))
        assertTrue("the engine must not run: $argvFile", invocations().isEmpty())
    }

    @Test
    fun anInitImageWhoseSizeIsNotAMultipleOfEightFailsWithAClearError() {
        val engine = fakeEngine(TestPng.file(dir, 64, 64, "generated.png"))
        val odd = patternedPng(File(dir, "odd.png"), 100, 64, 30)

        val code = run(
            "img2img",
            "--model", model.absolutePath,
            "--init-img", odd.absolutePath,
            "--out", outPng.absolutePath,
            "--engine", engine.absolutePath,
        )

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr must name the size: $err", err.contains("100x64"))
        assertTrue("stderr must name the rule: $err", err.contains("multiple of 8"))
        assertTrue("the engine must not run: $argvFile", invocations().isEmpty())
    }

    @Test
    fun aMissingInitImageFailsAndSaysSo() {
        val engine = fakeEngine(TestPng.file(dir, 64, 64, "generated.png"))
        val missing = File(dir, "gone.png")

        val code = run(
            "img2img",
            "--model", model.absolutePath,
            "--init-img", missing.absolutePath,
            "--out", outPng.absolutePath,
            "--engine", engine.absolutePath,
        )

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr must name the path: $err", err.contains(missing.absolutePath))
        assertTrue("the engine must not run: $argvFile", invocations().isEmpty())
    }

    @Test
    fun aStrengthOutsideZeroToOneIsAUsageErrorAndTheEngineNeverRuns() {
        val engine = fakeEngine(TestPng.file(dir, 64, 64, "generated.png"))
        for (bad in listOf("-0.1", "1.1", "2", "abc", "NaN", "Infinity")) {
            out.setLength(0); err.setLength(0)
            val code = img2img(engine, "--strength", bad)
            assertEquals("$bad should be a usage error, stderr: $err", 2, code)
            assertTrue("$bad must name --strength: $err", err.contains("--strength"))
            assertTrue("$bad must not print to stdout: $out", out.isEmpty())
        }
        assertTrue("the engine must never run on a bad strength: $argvFile", invocations().isEmpty())
    }

    // ---- metadata and the engine contract ----------------------------------

    @Test
    fun thePngCarriesStrengthAndInitImageInItsParameters() {
        val engine = fakeEngine(TestPng.file(dir, 64, 64, "generated.png"))

        val code = img2img(engine, "--strength", "0.4", "--seed", "1234")

        assertEquals("stderr: $err", 0, code)
        val text = parameters(outPng)
        assertTrue("the chunk must record strength: $text", text.contains("Strength: 0.4"))
        assertTrue("the chunk must record the init image: $text", text.contains("Init image: init.png"))
        assertTrue("the chunk must record the seed: $text", text.contains("Seed: 1234"))
        assertTrue("ImageIO must still decode the image", ImageIO.read(outPng) != null)
    }

    @Test
    fun anEngineFailureFailsTheCommandAndForwardsItsDiagnostics() {
        val engine = script("failing", exitCode = 4, body = "echo 'out of memory' >&2")

        val code = img2img(engine)

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr must name the exit code: $err", err.contains("4"))
        assertTrue("engine diagnostics must reach stdout: $out", out.contains("out of memory"))
        assertTrue("no image may be claimed: $outPng", !outPng.exists())
    }

    @Test
    fun anEngineOutputOfTheWrongSizeIsRejected() {
        val engine = fakeEngine(TestPng.file(dir, 32, 32, "wrong-size.png"))

        val code = img2img(engine)

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr must report the actual size: $err", err.contains("32x32"))
        assertTrue("stderr must report the requested size: $err", err.contains("64x64"))
    }

    @Test
    fun theEngineIsTakenFromNIGHTMARE_SD_BINWhenNoEngineFlagIsGiven() {
        val engine = fakeEngine(TestPng.file(dir, 64, 64, "generated.png"))

        val code = Cli.run(
            listOf(
                "img2img",
                "--model", model.absolutePath,
                "--init-img", initPng.absolutePath,
                "--out", outPng.absolutePath,
            ),
            out,
            err,
            mapOf("NIGHTMARE_SD_BIN" to engine.absolutePath),
        )

        assertEquals("stderr: $err", 0, code)
        assertTrue("the engine from the environment must have run: $argvFile", invocations().isNotEmpty())
    }
}
