package com.abrah.nightmare.desktop.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

/**
 * Seam: [Cli.run] plus the files it leaves behind. The engine is a shell script
 * the test writes, so every assertion here is about what a user observes --
 * the exit code, the two streams, the arguments that reach the engine, and the
 * PNG on disk -- and never about how the CLI is put together.
 *
 * No real model and no real engine: the "engine" is a few lines of `/bin/sh`.
 * The stub PNG is 24 bytes plus padding, which is exactly what the CLI is
 * contracted to inspect (signature + IHDR width/height).
 */
class Txt2ImgTest {

    private val out = StringBuilder()
    private val err = StringBuilder()

    private lateinit var dir: File
    private lateinit var model: File
    private lateinit var argvFile: File
    private lateinit var outPng: File

    @Before
    fun setUp() {
        assumeTrue("/bin/sh is required by the fake engine", File("/bin/sh").exists())
        dir = Files.createTempDirectory("nm-cli-test").toFile()
        model = File(dir, "model.safetensors").apply { writeText("not really a model") }
        argvFile = File(dir, "argv.txt")
        outPng = File(dir, "out.png")
    }

    private fun run(vararg args: String): Int = Cli.run(args.toList(), out, err)

    /** A script that records its arguments and copies [png] to whatever `-o` names. */
    private fun fakeEngine(png: File, name: String = "sd-cli", argvTo: File = argvFile): File {
        val script = File(dir, name)
        script.writeText(
            """
            #!/bin/sh
            for a in "${'$'}@"; do printf '%s\n' "${'$'}a"; done > '${argvTo.absolutePath}'
            echo 'fake engine: generating'
            out=''
            prev=''
            for a in "${'$'}@"; do
              if [ "${'$'}prev" = '-o' ]; then out="${'$'}a"; fi
              prev="${'$'}a"
            done
            cp '${png.absolutePath}' "${'$'}out"
            """.trimIndent() + "\n"
        )
        assertTrue("fake engine must be executable", script.setExecutable(true))
        return script
    }

    /** Minimal PNG header: signature, IHDR chunk length, "IHDR", width, height. */
    private fun stubPng(width: Int, height: Int, name: String = "stub.png"): File {
        val header = ByteBuffer.allocate(24)
        header.put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        header.putInt(13)
        header.put("IHDR".toByteArray(Charsets.US_ASCII))
        header.putInt(width)
        header.putInt(height)
        return File(dir, name).apply { writeBytes(header.array() + ByteArray(9)) }
    }

    private fun txt2img(engine: File, vararg extra: String): Int = run(
        "txt2img",
        "--model", model.absolutePath,
        "--prompt", "a red fox",
        "--out", outPng.absolutePath,
        "--engine", engine.absolutePath,
        *extra,
    )

    /** A script that does whatever [body] says and exits [exitCode]. */
    private fun script(name: String, exitCode: Int = 0, body: String): File {
        val file = File(dir, name)
        file.writeText("#!/bin/sh\n$body\nexit $exitCode\n")
        assertTrue("fake engine must be executable", file.setExecutable(true))
        return file
    }

    @Test
    fun `a non-zero engine exit fails the command and says so`() {
        val engine = script("failing", exitCode = 3, body = "echo 'out of memory' >&2")

        val code = txt2img(engine)

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr should name the engine exit code: $err", err.contains("3"))
        assertTrue("stderr should say the engine failed: $err", err.contains(engine.absolutePath))
        assertTrue("engine diagnostics must be forwarded: $out", out.contains("out of memory"))
    }

    @Test
    fun `a missing model fails before the engine is started`() {
        val engine = fakeEngine(stubPng(512, 512))
        model.delete()

        val code = txt2img(engine)

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr should name the model path: $err", err.contains(model.absolutePath))
        assertTrue("the engine must not run without a model: $argvFile", !argvFile.exists())
    }

    @Test
    fun `a missing engine binary fails and names the path it looked for`() {
        val missing = File(dir, "no-such-sd-cli")

        val code = txt2img(missing)

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr should name the engine path: $err", err.contains(missing.absolutePath))
        assertTrue("a failure must not print to stdout: $out", out.isEmpty())
    }

    @Test
    fun `an engine that exits zero without writing a file fails`() {
        val engine = script("silent", body = "echo 'pretending to work'")

        val code = txt2img(engine)

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr should name the missing output: $err", err.contains(outPng.absolutePath))
    }

    @Test
    fun `output that is not a PNG is rejected`() {
        val engine = script("not-a-png", body = "printf 'hello' > '${outPng.absolutePath}'")

        val code = txt2img(engine)

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr should say it is not a PNG: $err", err.contains("PNG"))
    }

    @Test
    fun `a PNG whose size differs from the request is rejected`() {
        val engine = fakeEngine(stubPng(64, 64), name = "wrong-size")

        val code = txt2img(engine, "--width", "512", "--height", "512")

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr should report the actual size: $err", err.contains("64x64"))
        assertTrue("stderr should report the requested size: $err", err.contains("512x512"))
    }

    @Test
    fun `txt2img without a prompt is a usage error`() {
        val code = run("txt2img", "--model", model.absolutePath, "--out", outPng.absolutePath)

        assertEquals("stderr: $err", 2, code)
        assertTrue("stderr should name --prompt: $err", err.contains("--prompt"))
        assertTrue("a usage error must not print to stdout: $out", out.isEmpty())
    }

    @Test
    fun `txt2img without a model is a usage error`() {
        val code = run("txt2img", "--prompt", "a red fox", "--out", outPng.absolutePath)

        assertEquals("stderr: $err", 2, code)
        assertTrue("stderr should name --model: $err", err.contains("--model"))
    }

    @Test
    fun `txt2img without an output path is a usage error`() {
        val code = run("txt2img", "--model", model.absolutePath, "--prompt", "a red fox")

        assertEquals("stderr: $err", 2, code)
        assertTrue("stderr should name --out: $err", err.contains("--out"))
    }

    @Test
    fun `an unknown txt2img option is a usage error`() {
        val code = run(
            "txt2img",
            "--model", model.absolutePath,
            "--prompt", "a red fox",
            "--out", outPng.absolutePath,
            "--frobnicate", "7",
        )

        assertEquals("stderr: $err", 2, code)
        assertTrue("stderr should name --frobnicate: $err", err.contains("--frobnicate"))
    }

    @Test
    fun `a non-numeric size is a usage error, not a silent default`() {
        val engine = fakeEngine(stubPng(512, 512))

        val code = txt2img(engine, "--width", "big")

        assertEquals("stderr: $err", 2, code)
        assertTrue("stderr should name --width: $err", err.contains("--width"))
        assertTrue("the engine must not run on a bad command line: $argvFile", !argvFile.exists())
    }

    @Test
    fun `a non-positive size is a usage error`() {
        val engine = fakeEngine(stubPng(512, 512))

        val code = txt2img(engine, "--height", "0")

        assertEquals("stderr: $err", 2, code)
        assertTrue("stderr should name --height: $err", err.contains("--height"))
    }

    @Test
    fun `the engine is taken from NIGHTMARE_SD_BIN when no --engine is given`() {
        val engine = fakeEngine(stubPng(512, 512))

        val code = Cli.run(
            listOf(
                "txt2img",
                "--model", model.absolutePath,
                "--prompt", "a red fox",
                "--out", outPng.absolutePath,
            ),
            out,
            err,
            mapOf("NIGHTMARE_SD_BIN" to engine.absolutePath),
        )

        assertEquals("stderr: $err", 0, code)
        assertTrue("the engine from the environment should have run: $argvFile", argvFile.isFile)
    }

    @Test
    fun `--engine wins over NIGHTMARE_SD_BIN`() {
        val envArgv = File(dir, "env-argv.txt")
        val fromEnv = fakeEngine(stubPng(512, 512), name = "from-env", argvTo = envArgv)
        val fromFlag = fakeEngine(stubPng(512, 512), name = "from-flag")

        val code = Cli.run(
            listOf(
                "txt2img",
                "--model", model.absolutePath,
                "--prompt", "a red fox",
                "--out", outPng.absolutePath,
                "--engine", fromFlag.absolutePath,
            ),
            out,
            err,
            mapOf("NIGHTMARE_SD_BIN" to fromEnv.absolutePath),
        )

        assertEquals("stderr: $err", 0, code)
        assertTrue("the flag's engine should have run: $argvFile", argvFile.isFile)
        assertTrue("the env engine must not run: $envArgv", !envArgv.exists())
    }

    @Test
    fun `with no engine anywhere the error names the default build path`() {
        val code = Cli.run(
            listOf("txt2img", "--model", model.absolutePath, "--prompt", "a red fox", "--out", outPng.absolutePath),
            out,
            err,
            emptyMap(),
        )

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr should name the default engine path: $err", err.contains("desktop/engine/build/bin/sd-cli"))
    }

    @Test
    fun `unspecified parameters fall back to 512 by 512, 20 steps and seed 42`() {
        val engine = fakeEngine(stubPng(512, 512))

        val code = run(
            "txt2img",
            "--model", model.absolutePath,
            "--prompt", "a red fox",
            "--out", outPng.absolutePath,
            "--engine", engine.absolutePath,
        )

        assertEquals("stderr: $err", 0, code)
        assertEquals(
            listOf(
                "-m", model.absolutePath,
                "-p", "a red fox",
                "-n", "",
                "-W", "512",
                "-H", "512",
                "--steps", "20",
                "--seed", "42",
                "-o", outPng.absolutePath,
            ),
            argvFile.readLines(),
        )
    }

    @Test
    fun `options also accept the --name=value form`() {
        val engine = fakeEngine(stubPng(768, 384))

        val code = run(
            "txt2img",
            "--model=${model.absolutePath}",
            "--prompt=a red fox",
            "--out=${outPng.absolutePath}",
            "--engine=${engine.absolutePath}",
            "--width=768",
            "--height=384",
        )

        assertEquals("stderr: $err", 0, code)
        assertTrue("the PNG written at the requested size should pass: $err", err.isEmpty())
    }

    @Test
    fun `txt2img hands the engine every parameter and succeeds`() {
        val engine = fakeEngine(stubPng(512, 512))

        val code = run(
            "txt2img",
            "--model", model.absolutePath,
            "--prompt", "a red fox",
            "--negative-prompt", "blurry",
            "--width", "512",
            "--height", "512",
            "--steps", "20",
            "--seed", "42",
            "--out", outPng.absolutePath,
            "--engine", engine.absolutePath,
        )

        assertEquals("stderr: $err", 0, code)
        assertEquals(
            listOf(
                "-m", model.absolutePath,
                "-p", "a red fox",
                "-n", "blurry",
                "-W", "512",
                "-H", "512",
                "--steps", "20",
                "--seed", "42",
                "-o", outPng.absolutePath,
            ),
            argvFile.readLines(),
        )
        assertTrue("engine output should reach stdout: $out", out.contains("fake engine: generating"))
        assertTrue("the PNG should be where --out said", outPng.isFile)
        assertTrue("success must not write to stderr: $err", err.isEmpty())
    }
}
