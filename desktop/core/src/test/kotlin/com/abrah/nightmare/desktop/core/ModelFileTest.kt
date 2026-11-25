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
 * Seam: [ModelFile.read] -- a path in, "what this file is" or an exception out.
 *
 * The fixtures are real file headers, written the way the formats say: a
 * safetensors file is an 8-byte little-endian header length, that many bytes of
 * JSON, then the weights; a GGUF file is `GGUF`, a version, a tensor count and
 * a key/value table. Nothing here asserts how the parse is implemented, only
 * what a caller can learn from it.
 */
class ModelFileTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("nm-modelfile-test").toFile()
    }

    // ---- fixtures ----------------------------------------------------------

    /** A safetensors file whose header declares [dtypes] by tensor name. */
    private fun safetensors(dtypes: Map<String, String>, metadata: Boolean = false): File {
        val entries = dtypes.entries.mapIndexed { i, (name, dtype) ->
            "\"$name\":{\"dtype\":\"$dtype\",\"shape\":[2],\"data_offsets\":[${i * 4},${i * 4 + 4}]}"
        }.toMutableList()
        if (metadata) entries += "\"__metadata__\":{\"format\":\"pt\"}"
        return safetensorsWithHeader("{" + entries.joinToString(",") + "}")
    }

    /** A safetensors file with [json] verbatim as its header. */
    private fun safetensorsWithHeader(json: String, name: String = "m.safetensors"): File {
        val header = json.toByteArray(Charsets.UTF_8)
        val bytes = ByteBuffer.allocate(8 + header.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(header.size.toLong())
            .put(header)
            .array()
        return File(dir, name).apply { writeBytes(bytes) }
    }

    /** A file whose first 8 bytes claim a [claimed] header length but hold [body]. */
    private fun safetensorsClaiming(claimed: Long, body: String = "{}", name: String = "claiming.safetensors"): File {
        val bytes = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(claimed)
            .array() + body.toByteArray(Charsets.UTF_8)
        return File(dir, name).apply { writeBytes(bytes) }
    }

    /** A GGUF v[version] file declaring [tensorNames], each F32. */
    private fun gguf(version: Int = 3, tensorNames: List<String> = listOf("w")): File {
        val out = java.io.ByteArrayOutputStream()
        fun u32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u64(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            u64(b.size.toLong())
            out.write(b)
        }
        out.write(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
        u32(version)
        u64(tensorNames.size.toLong())
        u64(0L) // no key/value pairs
        tensorNames.forEachIndexed { i, name ->
            str(name)
            u32(1) // one dimension
            u64(4L)
            u32(0) // F32
            u64(i * 4L)
        }
        return File(dir, "m.gguf").apply { writeBytes(out.toByteArray()) }
    }

    private fun expectIOException(block: () -> Unit): String {
        try {
            block()
        } catch (e: IOException) {
            return e.message ?: ""
        }
        fail("expected an IOException")
        throw IllegalStateException("unreachable")
    }

    // ---- safetensors -------------------------------------------------------

    @Test
    fun `a safetensors header reports its tensor count and dtypes`() {
        val contents = ModelFile.read(safetensors(mapOf("w" to "F16", "b" to "F32")).toPath())

        val header = contents as ModelFile.Contents.Safetensors
        assertEquals(2, header.tensors)
        assertEquals(setOf("F16", "F32"), header.dtypes)
        assertTrue("the header length should be the real one: ${header.headerBytes}", header.headerBytes > 0)
    }

    @Test
    fun `the metadata entry is not counted as a tensor`() {
        val contents = ModelFile.read(safetensors(mapOf("w" to "F16"), metadata = true).toPath())

        assertEquals(1, (contents as ModelFile.Contents.Safetensors).tensors)
    }

    @Test
    fun `a file whose header length is zero is not a safetensors file`() {
        val message = expectIOException { ModelFile.read(safetensorsClaiming(0).toPath()) }

        assertTrue("should say it is not safetensors: $message", message.contains("safetensors", ignoreCase = true))
    }

    @Test
    fun `a header that runs past the end of the file is reported, not thrown as a raw EOF`() {
        val message = expectIOException { ModelFile.read(safetensorsClaiming(1_000_000).toPath()) }

        assertTrue("should name the file's problem: $message", message.contains("safetensors", ignoreCase = true))
    }

    @Test
    fun `a header that is not JSON is reported`() {
        val message = expectIOException {
            ModelFile.read(safetensorsWithHeader("not json at all").toPath())
        }

        assertTrue("should say the header is unreadable: $message", message.contains("header", ignoreCase = true))
    }

    @Test
    fun `a truncated file is reported rather than crashing`() {
        val file = safetensors(mapOf("w" to "F16"))
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(10)) // 8 length bytes + 2 of JSON

        val message = expectIOException { ModelFile.read(file.toPath()) }

        assertTrue("should say the header is unreadable: $message", message.contains("header", ignoreCase = true))
    }

    // ---- gguf --------------------------------------------------------------

    @Test
    fun `a gguf header reports its version and tensor count`() {
        val contents = ModelFile.read(gguf(version = 3, tensorNames = listOf("w", "b")).toPath())

        val info = contents as ModelFile.Contents.Gguf
        assertEquals(3, info.version)
        assertEquals(2, info.tensors)
    }

    @Test
    fun `a gguf whose magic is wrong is reported as not gguf`() {
        val file = File(dir, "wrong.gguf").apply { writeBytes(ByteArray(64)) }

        val message = expectIOException { ModelFile.read(file.toPath()) }

        assertTrue("should say it is not a GGUF file: $message", message.contains("GGUF", ignoreCase = true))
    }

    // ---- extension ---------------------------------------------------------

    @Test
    fun `the format follows the extension, case-insensitively`() {
        assertEquals(ModelFile.Format.SAFETENSORS, ModelFile.formatOf(File("a.safetensors").toPath()))
        assertEquals(ModelFile.Format.SAFETENSORS, ModelFile.formatOf(File("A.SafeTensors").toPath()))
        assertEquals(ModelFile.Format.GGUF, ModelFile.formatOf(File("a.gguf").toPath()))
        assertEquals(null, ModelFile.formatOf(File("a.ckpt").toPath()))
        assertEquals(null, ModelFile.formatOf(File("noextension").toPath()))
    }
}
