package com.abrah.nightmare.desktop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * Seam: [ModelLibrary.scan] -- a directory in, three lists out (models,
 * files that claim to be models but are not, and everything else), plus
 * [ModelLibrary.resolve] for where the directory came from.
 *
 * The point of the third list is that nothing is silently skipped: a user who
 * cannot find their download in the listing can see it named, with the reason.
 */
class ModelLibraryTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("nm-library-test").toFile()
    }

    // ---- fixtures ----------------------------------------------------------

    private fun safetensors(name: String, tensors: Map<String, String> = mapOf("w" to "F16")): File {
        val json = "{" + tensors.entries.joinToString(",") { (t, dtype) ->
            "\"$t\":{\"dtype\":\"$dtype\",\"shape\":[2],\"data_offsets\":[0,4]}"
        } + "}"
        val header = json.toByteArray(Charsets.UTF_8)
        val bytes = ByteBuffer.allocate(8 + header.size).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(header.size.toLong()).put(header).array()
        return File(root, name).apply { parentFile.mkdirs(); writeBytes(bytes) }
    }

    private fun gguf(name: String, version: Int = 3, tensors: Int = 1): File {
        val out = java.io.ByteArrayOutputStream()
        fun u32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u64(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        out.write(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
        u32(version)
        u64(tensors.toLong())
        u64(0L)
        repeat(tensors) { i ->
            val b = "t$i".toByteArray(Charsets.UTF_8)
            u64(b.size.toLong()); out.write(b)
            u32(1); u64(4L); u32(0); u64(i * 4L)
        }
        return File(root, name).apply { parentFile.mkdirs(); writeBytes(out.toByteArray()) }
    }

    private fun other(name: String, content: String = "hello"): File =
        File(root, name).apply { parentFile.mkdirs(); writeText(content) }

    // ---- finding models ----------------------------------------------------

    @Test
    fun `nested model files are found with their format's metadata`() {
        safetensors("sd15/base.safetensors", mapOf("a" to "F16", "b" to "F32"))
        gguf("z-image/diffusion_model.gguf", version = 3, tensors = 7)

        val scan = ModelLibrary.scan(root.toPath())

        assertEquals(2, scan.found.size)
        val byName = scan.found.associateBy { it.path.fileName.toString() }
        val safetensors = byName.getValue("base.safetensors")
        assertEquals(setOf("F16", "F32"), safetensors.contents.dtypes)
        assertEquals(2, safetensors.contents.tensors)
        val gguf = byName.getValue("diffusion_model.gguf")
        assertEquals(3, (gguf.contents as ModelFile.Contents.Gguf).version)
        assertEquals(7, gguf.contents.tensors)
        assertTrue("the size should be the file's: ${safetensors.bytes}", safetensors.bytes > 0)
    }

    @Test
    fun `a model's path is the full path so it can be passed straight to --model`() {
        val file = safetensors("sd15/base.safetensors")

        val scan = ModelLibrary.scan(root.toPath())

        assertEquals(file.absolutePath, scan.found.single().path.toAbsolutePath().toString())
    }

    @Test
    fun `a file whose extension says model but whose bytes do not is rejected, not skipped`() {
        other("broken.safetensors", "this is not a safetensors header")
        safetensors("good.safetensors")

        val scan = ModelLibrary.scan(root.toPath())

        assertEquals(1, scan.found.size)
        assertEquals(1, scan.rejected.size)
        val rejected = scan.rejected.single()
        assertEquals("broken.safetensors", rejected.path.fileName.toString())
        assertTrue("the reason should name the problem: ${rejected.reason}", rejected.reason.contains("safetensors"))
    }

    @Test
    fun `a file this build cannot read is listed separately, not skipped`() {
        other("model.ckpt", "pickle")
        other("notes.txt", "hello")
        safetensors("good.safetensors")

        val scan = ModelLibrary.scan(root.toPath())

        assertEquals(1, scan.found.size)
        assertEquals(emptyList<Any>(), scan.rejected)
        assertEquals(
            listOf("model.ckpt", "notes.txt"),
            scan.others.map { it.fileName.toString() }.sorted(),
        )
    }

    @Test
    fun `an empty directory is an empty library, not an error`() {
        val scan = ModelLibrary.scan(root.toPath())

        assertEquals(0, scan.found.size)
        assertEquals(0, scan.rejected.size)
        assertEquals(0, scan.others.size)
    }

    @Test
    fun `a missing directory is an error that names the path and how to set it`() {
        val missing = File(root, "no-such-dir")

        try {
            ModelLibrary.scan(missing.toPath())
            fail("a missing directory must not scan as an empty library")
        } catch (e: IOException) {
            assertTrue("should name the path: ${e.message}", e.message!!.contains(missing.absolutePath))
            assertTrue("should say how to set the root: ${e.message}", e.message!!.contains(ModelLibrary.ENV_MODELS))
        }
    }

    @Test
    fun `a file where a directory is expected is an error`() {
        val file = other("not-a-dir")

        try {
            ModelLibrary.scan(file.toPath())
            fail("a file must not scan as an empty library")
        } catch (e: IOException) {
            assertTrue("should name the path: ${e.message}", e.message!!.contains(file.absolutePath))
        }
    }

    @Test
    fun `hidden files are not reported as unknown models`() {
        other(".gitkeep", "")

        val scan = ModelLibrary.scan(root.toPath())

        assertEquals(0, scan.others.size)
    }

    // ---- where the library lives -------------------------------------------

    @Test
    fun `--dir wins over NIGHTMARE_MODELS`() {
        val resolved = ModelLibrary.resolve(
            dirArg = "/tmp/explicit",
            env = mapOf(ModelLibrary.ENV_MODELS to "/tmp/from-env"),
        )

        assertEquals("/tmp/explicit", resolved.toString())
    }

    @Test
    fun `NIGHTMARE_MODELS is used when no --dir is given`() {
        val resolved = ModelLibrary.resolve(
            dirArg = null,
            env = mapOf(ModelLibrary.ENV_MODELS to "/tmp/from-env"),
        )

        assertEquals("/tmp/from-env", resolved.toString())
    }

    @Test
    fun `with neither, the shared cache under the home directory is used`() {
        val resolved = ModelLibrary.resolve(dirArg = null, env = emptyMap())

        assertTrue(
            "should be the documented shared cache, was $resolved",
            resolved.endsWith(".cache/nightmare-desktop/models"),
        )
        assertTrue(
            "should be under the home directory, was $resolved",
            resolved.startsWith(System.getProperty("user.home")),
        )
    }

    @Test
    fun `a blank NIGHTMARE_MODELS is ignored rather than scanning the working directory`() {
        val resolved = ModelLibrary.resolve(dirArg = null, env = mapOf(ModelLibrary.ENV_MODELS to "  "))

        assertTrue("should fall back to the shared cache, was $resolved", resolved.endsWith(".cache/nightmare-desktop/models"))
    }
}
