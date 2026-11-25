package com.abrah.nightmare.desktop.cli

/**
 * The command line itself was wrong: a missing value, an unknown option, a
 * number that is not a number. Caught before any file is opened, so it can
 * never be confused with "the work failed".
 */
internal class UsageException(message: String) : Exception(message)

/**
 * `--name value` and `--name=value`, checked against the options a command
 * knows. One parser for every command, so a flag accepted by `txt2img` and
 * rejected by `models` is a deliberate omission rather than an accident of
 * copy-paste.
 */
internal class Options private constructor(private val values: Map<String, String>) {

    operator fun get(name: String): String? = values[name]

    fun required(name: String): String {
        val value = values[name] ?: throw UsageException("missing required option '--$name'")
        if (value.isBlank()) throw UsageException("option '--$name' must not be empty")
        return value
    }

    /** [name] when given and non-blank, else null -- for options whose blank form is a mistake, not a default. */
    fun nonBlank(name: String): String? {
        val value = values[name] ?: return null
        if (value.isBlank()) throw UsageException("option '--$name' must not be empty")
        return value
    }

    fun positiveInt(name: String, default: Int): Int {
        val raw = values[name] ?: return default
        val value = raw.toIntOrNull()
            ?: throw UsageException("option '--$name' needs an integer, got '$raw'")
        if (value <= 0) throw UsageException("option '--$name' must be positive, got $value")
        return value
    }

    companion object {
        /** Throws [UsageException] on anything that is not `--known value` or `--known=value`. */
        fun parse(args: List<String>, known: Set<String>): Options {
            val values = LinkedHashMap<String, String>()
            var i = 0
            while (i < args.size) {
                val token = args[i]
                if (!token.startsWith("--")) {
                    throw UsageException("unexpected argument '$token'")
                }
                val body = token.removePrefix("--")
                val equals = body.indexOf('=')
                if (equals >= 0) {
                    val name = body.substring(0, equals)
                    requireKnown(name, known)
                    values[name] = body.substring(equals + 1)
                    i += 1
                } else {
                    requireKnown(body, known)
                    if (i + 1 >= args.size) {
                        throw UsageException("option '--$body' needs a value")
                    }
                    values[body] = args[i + 1]
                    i += 2
                }
            }
            return Options(values)
        }

        private fun requireKnown(name: String, known: Set<String>) {
            if (name !in known) throw UsageException("unrecognised option '--$name'")
        }
    }
}
