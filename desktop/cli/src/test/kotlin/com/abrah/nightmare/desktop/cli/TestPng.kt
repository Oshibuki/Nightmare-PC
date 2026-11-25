package com.abrah.nightmare.desktop.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * A **complete** PNG for the fake engine to "generate", and a strict reader for
 * what comes back.
 *
 * ⚠ The stub has to be a real file -- IHDR, a deflated IDAT, IEND, correct
 * CRC32s -- not just a 24-byte header: the CLI inserts the `parameters` chunk
 * *before IEND*, so a header-only stub could not tell "inserted in the right
 * place" from "appended past the end of the file", and the reader below would
 * have nothing to walk.
 *
 * The reader is deliberately independent of `Png.kt`: it recomputes every CRC32
 * and re-reads every chunk, so a bug in the writer cannot be mirrored by the
 * same bug in the checker.
 */
internal object TestPng {

    fun bytes(width: Int, height: Int, engineParameters: String? = null): ByteArray {
        val file = ByteArrayOutputStream()
        file.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteBuffer.allocate(13)
            .putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0)
            .array()
        writeChunk(file, "IHDR", ihdr)
        // The real engine writes its own `parameters` chunk right after IHDR,
        // so a stub that only has ours would never exercise the replace path.
        if (engineParameters != null) writeChunk(file, "tEXt", textData(engineParameters))
        val raw = ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0) // filter type: none
            raw.write(ByteArray(width * 3))
        }
        val idat = ByteArrayOutputStream()
        DeflaterOutputStream(idat).use { it.write(raw.toByteArray()) }
        writeChunk(file, "IDAT", idat.toByteArray())
        writeChunk(file, "IEND", ByteArray(0))
        return file.toByteArray()
    }

    private fun textData(text: String): ByteArray =
        Png.PARAMETERS_KEYWORD.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0) +
            text.toByteArray(Charsets.ISO_8859_1)

    fun file(dir: File, width: Int, height: Int, name: String = "stub-${width}x$height.png"): File =
        File(dir, name).apply { writeBytes(bytes(width, height)) }

    /** A stub that already carries an engine-written `parameters` chunk. */
    fun fileWithParameters(
        dir: File,
        width: Int,
        height: Int,
        text: String,
        name: String = "stub-params-${width}x$height.png",
    ): File = File(dir, name).apply { writeBytes(bytes(width, height, text)) }

    private fun writeChunk(into: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        into.write(ByteBuffer.allocate(4).putInt(data.size).array())
        into.write(typeBytes)
        into.write(data)
        into.write(ByteBuffer.allocate(4).putInt(crc32(typeBytes, data)).array())
    }

    private fun crc32(type: ByteArray, data: ByteArray): Int {
        val crc = CRC32()
        crc.update(type)
        crc.update(data)
        return crc.value.toInt()
    }

    /** Every chunk in order, failing loudly on a bad length or a bad CRC32. */
    fun chunks(file: File): List<Pair<String, ByteArray>> {
        val bytes = file.readBytes()
        val signature = listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (bytes.size < 8 || bytes.take(8).map { it.toInt() and 0xFF } != signature) {
            throw AssertionError("${file.name} does not start with the PNG signature")
        }
        val chunks = mutableListOf<Pair<String, ByteArray>>()
        var at = 8
        while (at + 12 <= bytes.size) {
            val length = ByteBuffer.wrap(bytes, at, 4).int
            if (length < 0 || at + 12 + length > bytes.size) {
                throw AssertionError("chunk at $at in ${file.name} claims $length bytes")
            }
            val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
            val data = bytes.copyOfRange(at + 8, at + 8 + length)
            val expected = ByteBuffer.wrap(bytes, at + 8 + length, 4).int
            val actual = crc32(type.toByteArray(Charsets.US_ASCII), data)
            if (expected != actual) {
                throw AssertionError(
                    "CRC32 of chunk '$type' in ${file.name} is ${actual.toUInt()}, file says ${expected.toUInt()}",
                )
            }
            chunks += type to data
            at += 12 + length
        }
        if (chunks.lastOrNull()?.first != "IEND") {
            throw AssertionError("the last chunk of ${file.name} is ${chunks.lastOrNull()?.first}, not IEND")
        }
        return chunks
    }

    /** The `parameters` text of a `tEXt` (or `iTXt`) chunk, or `null` when absent. */
    fun parameters(file: File): String? = parametersChunks(file).firstOrNull()

    /**
     * Every `parameters` chunk's text, in file order.
     *
     * ⚠ A list, not a single value: the engine writes its own `parameters`
     * chunk, so "the CLI added one" and "the file has two, disagreeing" are
     * different files, and only a count can tell them apart.
     */
    fun parametersChunks(file: File): List<String> {
        val found = mutableListOf<String>()
        for ((type, data) in chunks(file)) {
            if (type != "tEXt" && type != "iTXt") continue
            val keywordEnd = data.indexOf(0.toByte())
            if (keywordEnd < 0) continue
            if (String(data, 0, keywordEnd, Charsets.ISO_8859_1) != Png.PARAMETERS_KEYWORD) continue
            if (type == "tEXt") {
                found += String(data, keywordEnd + 1, data.size - keywordEnd - 1, Charsets.ISO_8859_1)
                continue
            }
            // iTXt: keyword \0 flag method language \0 translated \0 text
            var at = keywordEnd + 1
            val compressed = data[at].toInt() == 1
            at += 2
            at = data.indexOf(0.toByte(), at) + 1
            at = data.indexOf(0.toByte(), at) + 1
            val body = data.copyOfRange(at, data.size)
            found += if (compressed) {
                InflaterInputStream(body.inputStream()).readBytes().toString(Charsets.UTF_8)
            } else {
                String(body, Charsets.UTF_8)
            }
        }
        return found
    }

    private fun ByteArray.indexOf(value: Byte, from: Int = 0): Int {
        for (i in from until size) if (this[i] == value) return i
        return -1
    }
}
