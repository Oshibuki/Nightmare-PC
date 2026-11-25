package com.abrah.nightmare.desktop.cli

import com.abrah.nightmare.desktop.core.ProductIdentity
import java.io.File
import java.io.Flushable
import java.io.IOException

/**
 * `serve`: the opt-in A1111-compatible HTTP surface of the vendored engine.
 *
 * There is no background daemon and no implicit service: only this command
 * starts one, and it always passes `--listen-ip 127.0.0.1` to `sd-server`.
 * There is deliberately no host flag to undo that -- a tool that can be told
 * to bind a LAN address is one typo away from publishing an unauthenticated
 * generation endpoint (ticket 17, user story 13).
 *
 * The engine remains a child process. Its stdout and stderr are merged onto
 * the CLI's stdout as they arrive, the same as `txt2img`, so a long-lived
 * server says what it is doing and a crash cannot take the CLI down with it.
 * The foreground command ends when the engine ends; stopping the server is
 * the caller's Ctrl-C / process-manager signal.
 *
 * ⚠ This exposes the engine's own `/sdapi/v1/...` routes. It does not implement
 * an Android API layer (ticket 24) and does not claim certification against a
 * real A1111 client (ticket 25).
 */
internal object Serve {

    const val NAME: String = "serve"

    /** Where `desktop/engine/build.sh` leaves the CPU `sd-server` binary. */
    const val DEFAULT_ENGINE: String = "desktop/engine/build/bin/sd-server"

    const val ENV_ENGINE: String = "NIGHTMARE_SD_SERVER_BIN"

    /** The only address this command will ever hand to the engine. */
    const val LOOPBACK: String = "127.0.0.1"

    const val DEFAULT_PORT: Int = 1234

    private val PORT_RANGE = 1..65535

    private val KNOWN_OPTIONS = setOf("model", "port", "engine")

    fun run(args: List<String>, out: Appendable, err: Appendable, env: Map<String, String>): Int {
        val options = try {
            Options.parse(args, KNOWN_OPTIONS)
        } catch (e: UsageException) {
            err.append(usageError(e.message ?: "bad arguments"))
            return Cli.EXIT_USAGE
        }

        val modelPath = try {
            options.required("model")
        } catch (e: UsageException) {
            err.append(usageError(e.message ?: "bad arguments"))
            return Cli.EXIT_USAGE
        }
        val port = try {
            parsePort(options["port"])
        } catch (e: UsageException) {
            err.append(usageError(e.message ?: "bad arguments"))
            return Cli.EXIT_USAGE
        }

        val model = File(modelPath)
        if (!model.isFile) {
            err.append(failure("model '$modelPath' does not exist or is not a file"))
            return Cli.EXIT_FAILURE
        }
        if (!model.canRead()) {
            err.append(failure("model '$modelPath' is not readable"))
            return Cli.EXIT_FAILURE
        }

        val engine = File(options["engine"] ?: env[ENV_ENGINE] ?: DEFAULT_ENGINE)
        if (!engine.isFile) {
            err.append(
                failure(
                    "server '$engine' not found; build it with desktop/engine/build.sh " +
                        "or point --engine / \$$ENV_ENGINE at an sd-server binary",
                ),
            )
            return Cli.EXIT_FAILURE
        }
        if (!engine.canExecute()) {
            err.append(failure("server '$engine' is not executable"))
            return Cli.EXIT_FAILURE
        }

        out.append("Starting A1111 API at http://$LOOPBACK:$port\n")
        (out as? Flushable)?.flush()

        val code = try {
            runEngine(
                engine,
                listOf("-m", modelPath, "--listen-ip", LOOPBACK, "--listen-port", port.toString()),
                out,
            )
        } catch (e: IOException) {
            err.append(failure("cannot run server '$engine': ${e.message}"))
            return Cli.EXIT_FAILURE
        }
        if (code != 0) {
            err.append(failure("server '$engine' exited with code $code"))
            return Cli.EXIT_FAILURE
        }
        return Cli.EXIT_OK
    }

    private fun parsePort(raw: String?): Int {
        if (raw == null) return DEFAULT_PORT
        val value = raw.toIntOrNull()
            ?: throw UsageException("option '--port' needs an integer, got '$raw'")
        if (value !in PORT_RANGE) {
            throw UsageException("option '--port' must be between 1 and 65535, got $value")
        }
        return value
    }

    /**
     * Drain while the child is alive, never after `waitFor`: a chatty server
     * would otherwise fill the pipe and deadlock both processes. No internal
     * deadline -- a model load is slow but legitimate; callers bound the run.
     */
    private fun runEngine(engine: File, engineArgs: List<String>, out: Appendable): Int {
        val process = ProcessBuilder(listOf(engine.path) + engineArgs)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(8192)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                out.append(String(buffer, 0, read))
                (out as? Flushable)?.flush()
            }
        }
        return process.waitFor()
    }

    private fun usageError(reason: String): String =
        "${ProductIdentity.NAME}: $reason\n" +
            "Try '${ProductIdentity.NAME} $NAME --help' for more information.\n"

    private fun failure(reason: String): String = "${ProductIdentity.NAME}: $reason\n"
}
