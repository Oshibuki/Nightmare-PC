package com.abrah.nightmare.desktop.cli

import java.io.File
import java.io.IOException

/**
 * Just enough PNG to answer "did the engine write the image I asked for?".
 *
 * The engine already writes real PNGs; the risk this guards against is the
 * silent one -- a truncated file, a log accidentally redirected to `--out`, or
 * a build that quietly falls back to a different resolution. All three are
 * caught by the signature and the IHDR chunk, so only the first 24 bytes are
 * read and no image library is pulled in for Phase 0.
 */
internal object Png {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** IHDR is always the first chunk, and its data starts with width then height. */
    private const val HEADER_BYTES = 24

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

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
