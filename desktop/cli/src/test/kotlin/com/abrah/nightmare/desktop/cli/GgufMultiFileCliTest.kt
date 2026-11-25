package com.abrah.nightmare.desktop.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * Seam: [Cli.run] plus the files it leaves behind -- the same seam as
 * [Txt2ImgFullCliTest], extended to the multi-file DiT/GGUF pathway that tickets
 * 19-21 and 23 build on. The "engine" is a shell script that records the exact
 * argv it receives and copies a real PNG to whatever `-o` names, so every
 * assertion is about what a user observes: exit code, the two streams, the
 * engine's command line and the PNG on disk.
 *
 * No real weights and no real engine are involved; the model files here are
 * header-only fixtures with the bytes a real download would carry.
 */
class GgufMultiFileCliTest {

    private val out = StringBuilder()
    private val err = StringBuilder()

    private lateinit var dir: File
    private lateinit var diffusion: File
    private lateinit var llm: File
    private lateinit var vae: File
    private lateinit var vision: File
    private lateinit var argvFile: File
    private lateinit var outPng: File
    private lateinit var engine: File

    @Before
    fun setUp() {
        assumeTrue("/bin/sh is required by the fake engine", File("/bin/sh").exists())
        dir = Files.createTempDirectory("nm-cli-gguf-test").toFile()
        diffusion = gguf("diffusion_model.gguf")
        llm = gguf("llm.gguf")
        vae = safetensors("ae.safetensors")
        vision = gguf("mmproj.gguf")
        argvFile = File(dir, "argv.txt")
        outPng = File(dir, "out.png")
        engine = fakeEngine(TestPng.file(dir, 512, 512))
    }

    /** A GGUF whose header declares one F32 tensor -- the shape [ModelFile.read] expects. */
    private fun gguf(name: String): File {
        val bytes = ByteArrayOutputStream()
        fun u32(v: Int) = bytes.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u64(v: Long) = bytes.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        bytes.write(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
        u32(3); u64(1L); u64(0L)
        val weight = "w".toByteArray(Charsets.UTF_8)
        u64(weight.size.toLong()); bytes.write(weight)
        u32(1); u64(4L); u32(0); u64(0L)
        return File(dir, name).apply { writeBytes(bytes.toByteArray()) }
    }

    /** A safetensors header with one F16 tensor -- what a standalone VAE looks like. */
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

    /** Records every argv line by line, then copies the stub PNG to `-o`. */
    private fun fakeEngine(png: File): File {
        val script = File(dir, "sd-cli")
        script.writeText(
            """
            #!/bin/sh
            for a in "${'$'}@"; do printf '%s\n' "${'$'}a"; done > '${argvFile.absolutePath}'
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

    private fun runGguf(vararg extra: String): Int = runWith(diffusion, llm, vae, null, *extra)

    private fun runWith(
        diffusion: File,
        llm: File,
        vae: File,
        vision: File? = null,
        vararg extra: String,
    ): Int = Cli.run(
        buildList {
            add("txt2img")
            add("--diffusion-model"); add(diffusion.absolutePath)
            add("--llm"); add(llm.absolutePath)
            add("--vae"); add(vae.absolutePath)
            if (vision != null) {
                add("--llm_vision"); add(vision.absolutePath)
            }
            add("--prompt"); add("a red fox")
            add("--out"); add(outPng.absolutePath)
            add("--engine"); add(engine.absolutePath)
            addAll(extra)
        },
        out,
        err,
    )

    private fun runSingle(model: File, vararg extra: String): Int = Cli.run(
        listOf(
            "txt2img",
            "--model", model.absolutePath,
            "--prompt", "a red fox",
            "--out", outPng.absolutePath,
            "--engine", engine.absolutePath,
            *extra,
        ),
        out,
        err,
    )

    private fun argValue(flag: String): String? {
        val argv = argvFile.readLines()
        val i = argv.indexOf(flag)
        return if (i >= 0 && i + 1 < argv.size) argv[i + 1] else null
    }

    @Test
    fun `three gguf files reach the engine as a diffusion model, llm and vae`() {
        val code = runGguf()

        assertEquals("stderr: $err", 0, code)
        assertTrue("stderr must be empty on success: $err", err.isEmpty())
        assertEquals(diffusion.absolutePath, argValue("--diffusion-model"))
        assertEquals(llm.absolutePath, argValue("--llm"))
        assertEquals(vae.absolutePath, argValue("--vae"))
        assertTrue("-m must not be used for a multi-file model: ${argvFile.readLines()}", "-m" !in argvFile.readLines())
        assertTrue("a PNG should be written where --out said", outPng.isFile)
        assertTrue("the engine's own output must be forwarded: $out", out.contains("fake engine: generating"))
    }

    @Test
    fun `the three model selections each produce their documented engine command`() {
        // Single-file checkpoint, the three-file DiT set, and the four-file
        // Qwen Image set with a vision encoder. These are the three shapes the
        // downstream model tickets need from this pathway.
        val cases = listOf(
            { runSingle(safetensors("single.safetensors")) to listOf("-m") },
            { runWith(diffusion, llm, vae) to listOf("--diffusion-model", "--llm", "--vae") },
            { runWith(diffusion, llm, vae, vision) to listOf("--diffusion-model", "--llm", "--vae", "--llm_vision") },
        )
        for ((index, invoke) in cases.withIndex()) {
            out.setLength(0); err.setLength(0); argvFile.delete(); outPng.delete()
            val (code, flags) = invoke()
            assertEquals("case ${index + 1}: stderr: $err", 0, code)
            val argv = argvFile.readLines()
            for (flag in flags) {
                assertTrue("case ${index + 1} must pass $flag to the engine: $argv", flag in argv)
            }
            assertTrue("case ${index + 1} must produce a PNG", outPng.isFile)
        }
    }

    @Test
    fun `the vision encoder reaches the engine after the vae when given`() {
        val code = runWith(diffusion, llm, vae, vision)

        assertEquals("stderr: $err", 0, code)
        val argv = argvFile.readLines()
        assertEquals(vision.absolutePath, argValue("--llm_vision"))
        assertTrue(
            "--llm_vision must follow --vae in the engine command: $argv",
            argv.indexOf("--llm_vision") > argv.indexOf("--vae"),
        )
        assertTrue("Qwen Image must still get the diffusion model: $argv", "--diffusion-model" in argv)
    }

    @Test
    fun `new options also accept the --name=value form`() {
        val code = Cli.run(
            listOf(
                "txt2img",
                "--diffusion-model=${diffusion.absolutePath}",
                "--llm=${llm.absolutePath}",
                "--vae=${vae.absolutePath}",
                "--llm_vision=${vision.absolutePath}",
                "--prompt=a red fox",
                "--out=${outPng.absolutePath}",
                "--engine=${engine.absolutePath}",
            ),
            out,
            err,
        )

        assertEquals("stderr: $err", 0, code)
        assertEquals(diffusion.absolutePath, argValue("--diffusion-model"))
        assertEquals(vision.absolutePath, argValue("--llm_vision"))
    }

    @Test
    fun `standard parameters and metadata survive the multi-file pathway`() {
        engine = fakeEngine(TestPng.file(dir, 768, 384, "stub-768x384.png"))
        val code = runGguf(
            "--negative-prompt", "blurry",
            "--width", "768", "--height", "384",
            "--steps", "8", "--seed", "1234",
            "--sampler", "euler_a", "--scheduler", "karras", "--cfg-scale", "5.5",
        )

        assertEquals("stderr: $err", 0, code)
        val argv = argvFile.readLines()
        assertEquals("768", argv[argv.indexOf("-W") + 1])
        assertEquals("8", argv[argv.indexOf("--steps") + 1])
        assertEquals("euler_a", argv[argv.indexOf("--sampling-method") + 1])
        assertEquals(outPng.absolutePath, argv[argv.indexOf("-o") + 1])
        val metadata = TestPng.parameters(outPng) ?: throw AssertionError("no parameters chunk")
        assertTrue("metadata should name the diffusion model: $metadata", metadata.contains("Model: diffusion_model.gguf"))
        assertTrue("metadata should keep the prompt: $metadata", metadata.startsWith("a red fox\n"))
    }

    @Test
    fun `each missing multi-file path fails before the engine is started and names the option`() {
        val missing = File(dir, "not-downloaded.gguf")
        val cases = listOf(
            { runWith(missing, llm, vae) to "--diffusion-model" },
            { runWith(diffusion, missing, vae) to "--llm" },
            { runWith(diffusion, llm, missing) to "--vae" },
            { runWith(diffusion, llm, vae, missing) to "--llm_vision" },
        )
        for ((index, invoke) in cases.withIndex()) {
            out.setLength(0); err.setLength(0); argvFile.delete(); outPng.delete()
            val (code, option) = invoke()
            assertEquals("case ${index + 1}: stderr: $err", 1, code)
            assertTrue("case ${index + 1} must name $option: $err", err.contains(option))
            assertTrue("case ${index + 1} must name the bad path: $err", err.contains(missing.absolutePath))
            assertTrue("case ${index + 1} must not run the engine: $argvFile", !argvFile.exists())
            assertTrue("case ${index + 1} must not write a PNG", !outPng.exists())
        }
    }

    @Test
    fun `a corrupt multi-file model fails before the engine runs`() {
        val corrupt = File(dir, "truncated.gguf").apply { writeText("GGUF this is not really a header") }

        val code = runWith(corrupt, llm, vae)

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr should name the option: $err", err.contains("--diffusion-model"))
        assertTrue("stderr should say the file is unusable: $err", err.contains("not usable"))
        assertTrue("the engine must not run on a corrupt model: $argvFile", !argvFile.exists())
    }

    @Test
    fun `an unsupported extension on a multi-file option is a usage error`() {
        val wrong = File(dir, "encoder.ckpt").apply { writeText("pickle") }

        val code = runWith(diffusion, wrong, vae)

        assertEquals("stderr: $err", 2, code)
        assertTrue("stderr should name the option: $err", err.contains("--llm"))
        assertTrue("stderr should name the bad extension: $err", err.contains(".ckpt"))
        assertTrue("the engine must not run: $argvFile", !argvFile.exists())
    }

    @Test
    fun `a partial or doubled model selection is a usage error`() {
        val cases = listOf(
            listOf("--diffusion-model", diffusion.absolutePath, "--vae", vae.absolutePath) to "--llm",
            listOf("--diffusion-model", diffusion.absolutePath, "--llm", llm.absolutePath) to "--vae",
            listOf("--model", vae.absolutePath, "--diffusion-model", diffusion.absolutePath,
                "--llm", llm.absolutePath, "--vae", vae.absolutePath) to "--model",
            listOf("--model", vae.absolutePath, "--llm", llm.absolutePath) to "--llm",
            listOf("--llm", llm.absolutePath) to "--diffusion-model",
        )
        for ((index, case) in cases.withIndex()) {
            out.setLength(0); err.setLength(0); argvFile.delete()
            val code = Cli.run(
                listOf("txt2img", *case.first.toTypedArray(),
                    "--prompt", "a red fox", "--out", outPng.absolutePath, "--engine", engine.absolutePath),
                out, err,
            )
            assertEquals("case ${index + 1}: stderr: $err", 2, code)
            assertTrue("case ${index + 1} should name ${case.second}: $err", err.contains(case.second))
            assertTrue("case ${index + 1} must not run the engine: $argvFile", !argvFile.exists())
        }
    }

    @Test
    fun `a gguf handed to --model points at the now implemented multi-file pathway`() {
        val code = runSingle(diffusion)

        assertEquals("stderr: $err", 1, code)
        for (flag in listOf("--diffusion-model", "--llm", "--vae", "--llm_vision")) {
            assertTrue("stderr should name $flag as the way forward: $err", err.contains(flag))
        }
        assertTrue("stderr should say nothing was generated: $err", err.contains("nothing was generated"))
        assertTrue("the engine must not receive a GGUF via -m: $argvFile", !argvFile.exists())
    }
}
