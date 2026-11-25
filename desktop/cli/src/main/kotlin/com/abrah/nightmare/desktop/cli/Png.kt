package com.abrah.nightmare.desktop.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Just enough PNG to answer "did the engine write the image I asked for?", plus
 * the one chunk the CLI adds to it.
 *
 * The engine already writes real PNGs; the risk this guards against is the
 * silent one -- a truncated file, a log accidentally redirected to `--out`, or
 * a build that quietly falls back to a different resolution. All three are
 * caught by the signature and the IHDR chunk, so only the first 24 bytes are
 * read and no image library is pulled in.
 *
 * ⚠ The `parameters` chunk is written **before IEND**, never after it: IEND is
 * the last chunk by definition, and a reader that stops there would never see a
 * chunk written behind it. The CRC32 is recomputed over type+data, because a
 * chunk with a wrong CRC makes the whole file unreadable to a strict decoder --
 * a metadata feature that breaks the image is worse than no metadata.
 */
internal object Png {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** IHDR is always the first chunk, and its data starts with width then height. */
    private const val HEADER_BYTES = 24

    private const val IEND = "IEND"

    /** The chunk keyword A1111, ComfyUI and friends read generation parameters from. */
    const val PARAMETERS_KEYWORD: String = "parameters"

    data class Header(val width: Int, val height: Int) {
        override fun toString(): String = "${width}x$height"
    }

    /** The IHDR size, or `null` when [file] does not begin with a PNG signature. */
    fun header(file: File): Header? {
        val bytes = try {
            file.inputStream().use { it.readNBytes(HEADER_BYTES) }
        } catch (e: IOException) {
            return null
        }
        if (bytes.size < HEADER_BYTES) return null
        for (i in SIGNATURE.indices) {
            if (bytes[i] != SIGNATURE[i]) return null
        }
        // Chunk length (13) and type "IHDR" sit between the signature and the size.
        val isIhdr = bytes.copyOfRange(12, 16).contentEquals("IHDR".toByteArray(Charsets.US_ASCII))
        if (!isIhdr) return null
        return Header(width = readInt(bytes, 16), height = readInt(bytes, 20))
    }

    /**
     * Records [text] as the image's `parameters` chunk, in place.
     *
     * ⚠⚠ **Replace, not append.** The vendored engine writes its own
     * `parameters` chunk (keyword and all -- see `examples/cli/main.cpp`), so
     * appending ours would leave TWO chunks under one keyword holding two
     * different records of the same image. Readers disagree about which wins
     * (PIL's `PngImagePlugin` keeps the last, a chunk walker that stops at the
     * first match keeps the first), which makes the PNG's own metadata
     * non-deterministic. So an existing `parameters` chunk is overwritten where
     * it stands; when there is none, ours goes before IEND.
     *
     * The text is written as `tEXt` (ISO-8859-1, per the PNG spec) when it fits
     * that encoding, and as `iTXt` (UTF-8) when it does not -- a Japanese or
     * Chinese prompt is common enough that silently turning it into `?` would
     * make the chunk a lie about what was rendered. Both carry the same
     * keyword, and both are what A1111-compatible readers look for.
     *
     * Returns `false` when [file] is not a well-formed PNG with an IEND chunk,
     * which means the engine did not write what it claimed to.
     */
    fun addParameters(file: File, text: String): Boolean {
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            return false
        }
        val chunks = chunks(bytes) ?: return false
        if (chunks.lastOrNull()?.type != IEND) return false
        val replacement = if (isLatin1(text)) textChunk(text) else internationalTextChunk(text)

        val merged = ByteArrayOutputStream(bytes.size + replacement.size)
        var cursor = 0
        val existing = chunks.firstOrNull { isParametersChunk(bytes, it) }
        if (existing != null) {
            merged.write(bytes, 0, existing.at)
            merged.write(replacement)
            cursor = existing.at + existing.length
        } else {
            val iend = chunks.last()
            merged.write(bytes, 0, iend.at)
            merged.write(replacement)
            cursor = iend.at
        }
        merged.write(bytes, cursor, bytes.size - cursor)
        return try {
            file.writeBytes(merged.toByteArray())
            true
        } catch (e: IOException) {
            false
        }
    }

    private class Chunk(val at: Int, val length: Int, val type: String)

    /** Every chunk with its offset, or `null` if the chunk list does not parse. */
    private fun chunks(bytes: ByteArray): List<Chunk>? {
        if (bytes.size < 8) return null
        for (i in SIGNATURE.indices) {
            if (bytes[i] != SIGNATURE[i]) return null
        }
        val chunks = mutableListOf<Chunk>()
        var at = SIGNATURE.size
        while (at + 12 <= bytes.size) {
            val length = readInt(bytes, at)
            if (length < 0 || at + 12 + length > bytes.size) return null
            chunks += Chunk(at, 12 + length, String(bytes, at + 4, 4, Charsets.US_ASCII))
            at += 12 + length
        }
        return chunks
    }

    private fun isParametersChunk(bytes: ByteArray, chunk: Chunk): Boolean {
        if (chunk.type != "tEXt" && chunk.type != "iTXt") return false
        val dataAt = chunk.at + 8
        val dataEnd = chunk.at + chunk.length - 4
        var keywordEnd = dataAt
        while (keywordEnd < dataEnd && bytes[keywordEnd] != 0.toByte()) keywordEnd++
        if (keywordEnd >= dataEnd) return false
        val keyword = String(bytes, dataAt, keywordEnd - dataAt, Charsets.ISO_8859_1)
        return keyword == PARAMETERS_KEYWORD
    }

    private fun isLatin1(text: String): Boolean = text.all { it.code <= 0xFF }

    /** `tEXt`: keyword \0 text, both ISO-8859-1. */
    private fun textChunk(text: String): ByteArray = chunk(
        "tEXt",
        PARAMETERS_KEYWORD.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0) +
            text.toByteArray(Charsets.ISO_8859_1),
    )

    /** `iTXt`: keyword \0 flag(0) method(0) language \0 translated \0 text-UTF-8. */
    private fun internationalTextChunk(text: String): ByteArray = chunk(
        "iTXt",
        PARAMETERS_KEYWORD.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0) +
            byteArrayOf(0, 0) +
            byteArrayOf(0) +
            byteArrayOf(0) +
            text.toByteArray(Charsets.UTF_8),
    )

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        return ByteBuffer.allocate(12 + data.size)
            .putInt(data.size)
            .put(typeBytes)
            .put(data)
            .putInt(crc.value.toInt())
            .array()
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
