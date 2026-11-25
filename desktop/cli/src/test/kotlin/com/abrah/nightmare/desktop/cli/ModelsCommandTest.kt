package com.abrah.nightmare.desktop.cli

import com.abrah.nightmare.desktop.core.ModelLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * Seam: [Cli.run] with `models list` -- a directory in, a listing out.
 *
 * The assertions are about what a person reading the output can learn: every
 * model file appears with the path they can pass to `--model`, a file that is
 * not what its name claims appears with the reason, and a library that is
 * empty is not an error while a path that does not exist is.
 */
class ModelsCommandTest {

    private val out = StringBuilder()
    private val err = StringBuilder()

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("nm-models-test").toFile()
    }

    private fun run(vararg args: String): Int = Cli.run(args.toList(), out, err)

    private fun runWithEnv(env: Map<String, String>, vararg args: String): Int =
        Cli.run(args.toList(), out, err, env)

    // ---- fixtures ----------------------------------------------------------

    private fun safetensors(name: String, tensors: Int = 2): File {
        val json = "{" + (0 until tensors).joinToString(",") { i ->
            "\"t$i\":{\"dtype\":\"F16\",\"shape\":[2],\"data_offsets\":[${i * 4},${i * 4 + 4}]}"
        } + "}"
        val header = json.toByteArray(Charsets.UTF_8)
        val bytes = ByteBuffer.allocate(8 + header.size).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(header.size.toLong()).put(header).array()
        return File(dir, name).apply { parentFile.mkdirs(); writeBytes(bytes) }
    }

    private fun gguf(name: String, version: Int = 3, tensors: Int = 4): File {
        val bytes = java.io.ByteArrayOutputStream()
        fun u32(v: Int) = bytes.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u64(v: Long) = bytes.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        bytes.write(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
        u32(version); u64(tensors.toLong()); u64(0L)
        repeat(tensors) { i ->
            val b = "t$i".toByteArray(Charsets.UTF_8)
            u64(b.size.toLong()); bytes.write(b)
            u32(1); u64(4L); u32(0); u64(i * 4L)
        }
        return File(dir, name).apply { parentFile.mkdirs(); writeBytes(bytes.toByteArray()) }
    }

    private fun other(name: String, content: String = "not a model"): File =
        File(dir, name).apply { parentFile.mkdirs(); writeText(content) }

    // ---- the listing -------------------------------------------------------

    @Test
    fun `every model file is listed with the path --model takes`() {
        val sd15 = safetensors("sd15/base.safetensors", tensors = 3)
        val gguf = gguf("z-image/diffusion_model.gguf", version = 3, tensors = 7)

        val code = run("models", "list", "--dir", dir.absolutePath)

        assertEquals("stderr: $err", 0, code)
        assertTrue("stderr must stay empty on success: $err", err.isEmpty())
        val text = out.toString()
        assertTrue("should list the safetensors path: $text", text.contains(sd15.absolutePath))
        assertTrue("should list the gguf path: $text", text.contains(gguf.absolutePath))
        assertTrue("should say how many tensors: $text", text.contains("3 tensors"))
        assertTrue("should say how many tensors: $text", text.contains("7 tensors"))
        assertTrue("should name the GGUF version: $text", text.contains("GGUF v3"))
    }

    @Test
    fun `a file whose extension lies is listed with the reason, not dropped`() {
        safetensors("good.safetensors")
        val broken = other("broken.safetensors", "definitely not a header")

        val code = run("models", "list", "--dir", dir.absolutePath)

        assertEquals("stderr: $err", 0, code)
        val text = out.toString()
        assertTrue("should name the broken file: $text", text.contains(broken.absolutePath))
        assertTrue("should say why: $text", text.contains("not a safetensors file"))
    }

    @Test
    fun `a file in a format this build does not read is listed, not dropped`() {
        val ckpt = other("legacy.ckpt", "pickle")

        val code = run("models", "list", "--dir", dir.absolutePath)

        assertEquals("stderr: $err", 0, code)
        assertTrue("should name the unsupported file: $out", out.contains(ckpt.absolutePath))
    }

    @Test
    fun `an empty library succeeds and says so`() {
        val code = run("models", "list", "--dir", dir.absolutePath)

        assertEquals("stderr: $err", 0, code)
        assertTrue("should say there are no models: $out", out.contains("no models", ignoreCase = true))
        assertTrue("stderr must stay empty: $err", err.isEmpty())
    }

    @Test
    fun `a library that does not exist fails and names the path`() {
        val missing = File(dir, "no-such-dir")

        val code = run("models", "list", "--dir", missing.absolutePath)

        assertEquals(1, code)
        assertTrue("should name the path: $err", err.contains(missing.absolutePath))
        assertTrue("should say how to set the root: $err", err.contains(ModelLibrary.ENV_MODELS))
        assertTrue("a failure must not print a listing: $out", out.isEmpty())
    }

    @Test
    fun `NIGHTMARE_MODELS supplies the directory when --dir is absent`() {
        val model = safetensors("env/base.safetensors")

        val code = runWithEnv(mapOf(ModelLibrary.ENV_MODELS to dir.absolutePath), "models", "list")

        assertEquals("stderr: $err", 0, code)
        assertTrue("should list the model found through the environment: $out", out.contains(model.absolutePath))
    }

    @Test
    fun `--dir wins over NIGHTMARE_MODELS`() {
        val here = safetensors("here/base.safetensors")
        val elsewhere = Files.createTempDirectory("nm-models-elsewhere").toFile()
        File(elsewhere, "other.safetensors").writeBytes(here.readBytes())

        val code = runWithEnv(
            mapOf(ModelLibrary.ENV_MODELS to elsewhere.absolutePath),
            "models", "list", "--dir", dir.absolutePath,
        )

        assertEquals("stderr: $err", 0, code)
        assertTrue("should list --dir's model: $out", out.contains(here.absolutePath))
        assertTrue("must not list the environment's model: $out", !out.contains("other.safetensors"))
    }

    // ---- usage -------------------------------------------------------------

    @Test
    fun `models without a subcommand is a usage error`() {
        val code = run("models")

        assertEquals(2, code)
        assertTrue("should name the subcommand: $err", err.contains("list"))
        assertTrue("a usage error must not print a listing: $out", out.isEmpty())
    }

    @Test
    fun `an unknown models subcommand is a usage error`() {
        val code = run("models", "frobnicate")

        assertEquals(2, code)
        assertTrue("should name the offending subcommand: $err", err.contains("frobnicate"))
    }

    @Test
    fun `an unknown models option is a usage error`() {
        val code = run("models", "list", "--frobnicate", "7")

        assertEquals(2, code)
        assertTrue("should name the offending option: $err", err.contains("--frobnicate"))
    }

    @Test
    fun `help documents the models command`() {
        assertEquals(0, run("--help"))

        assertTrue("help should name models list: $out", out.contains("models list"))
        assertTrue("help should name --dir: $out", out.contains("--dir"))
    }

    // ---- the real library on this machine ----------------------------------

    /**
     * ⚠ Skipped where the shared cache is absent -- CI has no 4 GB checkpoint.
     * It reads headers only, so it costs a second, and it is the one test that
     * proves the scan against weights nobody fabricated.
     */
    @Test
    fun `the shared cache on this machine is listed with its safetensors and gguf models`() {
        val root = ModelLibrary.defaultRoot()
        assumeTrue("no shared model cache at $root", root.toFile().isDirectory)

        val code = run("models", "list")

        assertEquals("stderr: $err", 0, code)
        val text = out.toString()
        assertTrue(
            "should find the SD1.5 smoke checkpoint: $text",
            text.contains("sd15${File.separator}v1-5-pruned-emaonly.safetensors"),
        )
        assertTrue(
            "should find the Z-Image diffusion model: $text",
            text.contains("z-image${File.separator}diffusion_model.gguf"),
        )
        assertTrue("should report it as GGUF: $text", text.contains("GGUF v"))
    }
}
