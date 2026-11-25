package com.abrah.nightmare.desktop.core

import com.abrah.nightmare.GgufHeader
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

/**
 * ⭐⭐ **What a model file on disk actually is**, judged by its extension *and*
 * its first bytes -- never by its name alone.
 *
 * A `.safetensors` from CivitAI, a `.gguf` from a quantiser, and a half-finished
 * download all arrive looking alike in `ls`. The engine's failure for the last
 * one is minutes of model loading followed by a crash or, worse, a noise image;
 * this answers in milliseconds, before any engine starts.
 *
 * ⚠ Two formats, one shape of answer: [Contents.Safetensors] and
 * [Contents.Gguf] both say how many tensors and which weight types, because
 * that is what a person wants to know about a file they just downloaded, and
 * what `models list` prints.
 *
 * ⚠ Only the header is read -- at most [HEAD_BYTES] -- so a 6.5 GB checkpoint
 * costs one small read.
 */
object ModelFile {

    /** How much of a file is read to describe it. A GGUF's table can be 16 MB (a text encoder's vocabulary). */
    const val HEAD_BYTES: Int = 16 shl 20

    /** A safetensors header past this is not a header: 64 MiB is llama.cpp's own limit for one. */
    private const val MAX_SAFETENSORS_HEADER: Long = 64L shl 20

    enum class Format(val extension: String) {
        SAFETENSORS("safetensors"),
        GGUF("gguf"),
    }

    /** The formats this CLI can read, by their extension. */
    val EXTENSIONS: List<String> = Format.values().map { it.extension }

    sealed class Contents {
        abstract val tensors: Int
        abstract val dtypes: Set<String>

        /** A tensor table read from the 8-byte length + JSON header. */
        data class Safetensors(
            override val tensors: Int,
            override val dtypes: Set<String>,
            /** The header's own declared length, in bytes. */
            val headerBytes: Long,
        ) : Contents()

        /** A tensor table read with [GgufHeader]; `version` is the file's GGUF version (2 or 3 today). */
        data class Gguf(
            override val tensors: Int,
            override val dtypes: Set<String>,
            val version: Int,
        ) : Contents()
    }

    /**
     * The format [path]'s name claims, or null when the extension is not one we
     * can read. Case-insensitive: a Windows copy of `Model.GGUF` is the same file.
     */
    fun formatOf(path: Path): Format? {
        val name = path.fileName?.toString() ?: return null
        val extension = name.substringAfterLast('.', "").lowercase()
        return Format.values().firstOrNull { it.extension == extension }
    }

    /**
     * Describes [path], reading only its header.
     *
     * ⚠ Throws [IOException] when the extension and the bytes disagree, when the
     * file is truncated, or when the header is malformed -- each message says
     * which of those it is, because "load failed" is not a diagnosis.
     */
    fun read(path: Path): Contents = when (val format = formatOf(path)) {
        Format.SAFETENSORS -> readSafetensors(path)
        Format.GGUF -> readGguf(path)
        null -> throw IOException(
            "'${path.fileName}' is not a model file this build reads " +
                "(supported: ${EXTENSIONS.joinToString(", ") { ".$it" }})",
        )
    }

    private fun readSafetensors(path: Path): Contents {
        val file = path.toFile()
        val length = file.length()
        val head = file.inputStream().use { stream ->
            val buffer = ByteArray(minOf(length, 8L + MAX_SAFETENSORS_HEADER).toInt())
            var read = 0
            while (read < buffer.size) {
                val n = stream.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        }
        if (head.size < 8) {
            throw IOException("'${path.fileName}' is not a safetensors file: it is ${head.size} bytes, too short for a header")
        }
        val headerBytes = ByteBuffer.wrap(head, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        if (headerBytes <= 0 || headerBytes > MAX_SAFETENSORS_HEADER) {
            throw IOException("'${path.fileName}' is not a safetensors file: its header length is $headerBytes")
        }
        if (headerBytes > length - 8) {
            throw IOException(
                "'${path.fileName}' is not a safetensors file: its header claims $headerBytes bytes " +
                    "but the file holds ${length - 8} after the length prefix",
            )
        }
        val json = String(head, 8, headerBytes.toInt(), Charsets.UTF_8)
        val header = try {
            MiniJson.parse(json) as? Map<*, *>
                ?: throw IOException("its header is not a JSON object")
        } catch (e: MiniJson.Malformed) {
            throw IOException("'${path.fileName}' has a safetensors header that is not readable JSON: ${e.message}")
        }
        // ⚠ `__metadata__` is the one key that is not a tensor: it holds the
        // training framework's own notes and has no `dtype`.
        val tensors = header.filterKeys { it != "__metadata__" }
        val dtypes = tensors.values.mapTo(LinkedHashSet()) { entry ->
            (entry as? Map<*, *>)?.get("dtype") as? String
                ?: throw IOException("'${path.fileName}' has a safetensors header with no dtype for a tensor")
        }
        return Contents.Safetensors(tensors.size, dtypes, headerBytes)
    }

    private fun readGguf(path: Path): Contents {
        val head = path.toFile().inputStream().use { stream ->
            val buffer = ByteArray(minOf(path.toFile().length(), HEAD_BYTES.toLong()).toInt())
            var read = 0
            while (read < buffer.size) {
                val n = stream.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        }
        // ⚠ The parse is [GgufHeader]'s: the same table the phone reads, so a
        // file that lists here is one the engine can be asked about. Only the
        // version is read here, because the copied table does not carry it.
        val table = GgufHeader.parse(head)
        val version = ByteBuffer.wrap(head, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return Contents.Gguf(table.tensors, table.types, version)
    }

    /** True when [path] can be opened for reading -- what `--model` needs before anything else. */
    fun isReadable(path: Path): Boolean = runCatching {
        path.toFile().inputStream().use { it.read() }
    }.isSuccess

    /** The file size in bytes, or 0 when it cannot be read. */
    fun sizeOf(path: Path): Long = File(path.toString()).length()
}

/**
 * ⭐ A JSON reader small enough to keep the desktop build free of a dependency.
 *
 * ⚠⚠ It exists because a safetensors header must be *validated*, not scanned:
 * the mobile copy of this reader ([SafetensorsGguf]) uses `org.json`, which is
 * an Android API and is not on the desktop classpath, and a substring search
 * for `"dtype"` would accept a truncated file. Objects come back as
 * `LinkedHashMap` (so key order survives), arrays as `List`, numbers as `Double`
 * -- the header holds offsets and shapes, never a value this build computes with.
 */
internal object MiniJson {

    class Malformed(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipWhitespace()
        if (!reader.done) throw Malformed("trailing text at offset ${reader.at}")
        return value
    }

    private class Reader(val text: String) {
        var at = 0

        val done: Boolean get() = at >= text.length

        fun skipWhitespace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun fail(what: String): Nothing = throw Malformed("$what at offset $at")

        private fun take(expected: Char) {
            skipWhitespace()
            if (done || text[at] != expected) fail("expected '$expected'")
            at++
        }

        fun value(): Any? {
            skipWhitespace()
            if (done) fail("unexpected end of header")
            return when (val c = text[at]) {
                '{' -> obj()
                '[' -> list()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, at)) fail("expected '$word'")
            at += word.length
            return value
        }

        private fun obj(): Map<String, Any?> {
            take('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!done && text[at] == '}') {
                at++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = string()
                take(':')
                map[key] = value()
                skipWhitespace()
                if (done) fail("unterminated object")
                when (text[at]) {
                    ',' -> at++
                    '}' -> {
                        at++
                        return map
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun list(): List<Any?> {
            take('[')
            val items = ArrayList<Any?>()
            skipWhitespace()
            if (!done && text[at] == ']') {
                at++
                return items
            }
            while (true) {
                items += value()
                skipWhitespace()
                if (done) fail("unterminated array")
                when (text[at]) {
                    ',' -> at++
                    ']' -> {
                        at++
                        return items
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            skipWhitespace()
            if (done || text[at] != '"') fail("expected a string")
            at++
            val out = StringBuilder()
            while (true) {
                if (done) fail("unterminated string")
                when (val c = text[at++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (done) fail("unterminated escape")
                        when (val e = text[at++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (at + 4 > text.length) fail("truncated \\u escape")
                                val hex = text.substring(at, at + 4)
                                out.append(hex.toIntOrNull(16)?.toChar() ?: fail("bad \\u escape '$hex'"))
                                at += 4
                            }
                            else -> fail("unknown escape '\\$e'")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = at
            if (!done && text[at] == '-') at++
            while (!done && (text[at] in '0'..'9' || text[at] in ".eE+-")) at++
            return text.substring(start, at).toDoubleOrNull() ?: fail("bad number '${text.substring(start, at)}'")
        }
    }
}
