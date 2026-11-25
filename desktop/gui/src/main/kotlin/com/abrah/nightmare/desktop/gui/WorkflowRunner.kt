package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.desktop.graph.DesktopNodeTypes
import com.abrah.nightmare.desktop.graph.Workflow
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives the stateless CLI as a child process and owns its handle (ADR-0009).
 * The GUI projects the workflow into arguments here; it never calls `Cli.run`
 * or any internal generation function in-process.
 */
class WorkflowRunner(private val config: RunConfig = RunConfig()) {

    private val lock = Any()
    private val listeners = mutableListOf<() -> Unit>()
    @Volatile private var currentSnapshot = RunSnapshot()
    @Volatile private var process: Process? = null

    /** Returns an unsubscribe function so UI listeners do not outlive the screen. */
    fun addChangeListener(listener: () -> Unit): () -> Unit {
        synchronized(lock) { listeners += listener }
        return { synchronized(lock) { listeners -= listener } }
    }

    /** One immutable read of the run state for shells and external tools. */
    fun snapshot(): RunSnapshot = currentSnapshot

    fun isRunning(): Boolean = currentSnapshot.status == RunStatus.RUNNING

    /** The live child handle, exposed so a shell can end it on window close. */
    fun processHandle(): Process? = process

    /**
     * Starts one run and blocks until the child exits. Returns false when a run
     * is already in flight, so the shell can disable 「运行」 while busy.
     */
    fun run(workflow: Workflow): Boolean {
        synchronized(lock) {
            if (currentSnapshot.status == RunStatus.RUNNING) return false
            currentSnapshot = RunSnapshot(status = RunStatus.RUNNING, command = project(workflow))
        }
        val runParams = paramsFor(workflow)
        notifyChanged()

        val builder = ProcessBuilder(currentSnapshot.command)
        config.env.forEach { (key, value) -> builder.environment()[key] = value }
        val child = try {
            builder.start()
        } catch (ex: java.io.IOException) {
            val message = "cannot start CLI '${config.cliExecutable}': ${ex.message}"
            synchronized(lock) {
                currentSnapshot = currentSnapshot.copy(
                    status = RunStatus.FAILURE,
                    error = message,
                    log = listOf(message),
                )
                process = null
            }
            notifyChanged()
            return true
        }
        synchronized(lock) { process = child }

        val timedOut = AtomicBoolean(false)
        val watchdog = Thread {
            if (!child.waitFor(config.timeoutMillis, TimeUnit.MILLISECONDS)) {
                timedOut.set(true)
                child.destroyForcibly()
            }
        }.apply { isDaemon = true }
        watchdog.start()

        // One log panel for every raw line, from both streams. Progress lines
        // are parsed as well as logged -- the two views are not alternatives.
        val logLines = Collections.synchronizedList(mutableListOf<String>())
        fun appendLog(line: String) {
            logLines += line
            val snapshotLog = synchronized(logLines) { logLines.toList() }
            synchronized(lock) {
                currentSnapshot = currentSnapshot.copy(log = snapshotLog)
            }
            notifyChanged()
        }

        // Drain stderr on its own thread so a chatty failure cannot fill the
        // pipe and stall the child while stdout is still being consumed.
        val stderrDrain = Thread {
            child.errorStream.bufferedReader().useLines { lines -> lines.forEach(::appendLog) }
        }.apply { isDaemon = true }
        stderrDrain.start()

        val events = mutableListOf<ProgressEvent>()
        try {
            child.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    appendLog(line)
                    val event = ProgressLines.parse(line) ?: return@forEach
                    synchronized(lock) {
                        events += event
                        currentSnapshot = currentSnapshot.copy(progress = events.toList())
                    }
                    notifyChanged()
                }
            }
        } catch (ex: java.io.IOException) {
            // A destroyed or crashed child may close stdout abruptly. Keep the
            // partial log and let finalization below settle the run state.
            appendLog("CLI stdout ended unexpectedly: ${ex.message}")
        }
        stderrDrain.join(STDERR_JOIN_MS)
        child.waitFor(config.timeoutMillis, TimeUnit.MILLISECONDS)

        val didTimeOut = timedOut.get()
        val exitCode = if (didTimeOut) TIMEOUT_EXIT_CODE else child.exitValue()
        val status = if (exitCode == 0) RunStatus.SUCCESS else RunStatus.FAILURE
        val error = when {
            exitCode == 0 -> null
            didTimeOut -> "CLI timed out after ${config.timeoutMillis} ms and was stopped"
            else -> "CLI exited with code $exitCode"
        }
        // A progress line is a promise, not a PNG. Only files that really exist
        // after a clean exit are offered to the result panel; the seed in each
        // result is the one the CLI actually used for that image.
        val results = if (exitCode == 0) {
            events.filter { File(it.path).isFile }.map { event ->
                ResultArtifact(event.path, runParams + ("seed" to event.seed.toString()))
            }
        } else {
            emptyList()
        }
        val finalLog = synchronized(logLines) { logLines.toList() }
        synchronized(lock) {
            currentSnapshot = currentSnapshot.copy(
                status = status,
                error = error,
                log = finalLog,
                results = results,
            )
            process = null
        }
        notifyChanged()
        return true
    }

    /** Workflow → CLI arguments. The CLI keeps no state between invocations. */
    private fun project(workflow: Workflow): List<String> {
        val args = mutableListOf(config.cliExecutable, "txt2img")
        config.modelPath?.takeIf { it.isNotBlank() }?.let { args += listOf("--model", it) }
        val promptNode = workflow.nodes.firstOrNull { it.type == DesktopNodeTypes.PROMPT }
        val samplerNode = workflow.nodes.firstOrNull { it.type == DesktopNodeTypes.SD_SAMPLER }
        promptNode?.params?.get("prompt")?.let { args += listOf("--prompt", it) }
        promptNode?.params?.get("negative")?.takeIf { it.isNotBlank() }
            ?.let { args += listOf("--negative-prompt", it) }
        samplerNode?.params?.let { params ->
            listOf(
                "--sampler" to params["sampler"],
                "--scheduler" to params["scheduler"],
                "--steps" to params["steps"],
                "--cfg-scale" to params["cfgScale"],
                "--seed" to params["seed"],
                "--width" to params["width"],
                "--height" to params["height"],
            ).forEach { (flag, value) ->
                if (!value.isNullOrBlank()) args += listOf(flag, value)
            }
        }
        args += listOf("--out", config.outputPath)
        args += listOf("--batch", config.batch.toString())
        return args
    }

    /** The workflow settings that produced this run, attached to each result. */
    private fun paramsFor(workflow: Workflow): Map<String, String> {
        val prompt = workflow.nodes.firstOrNull { it.type == DesktopNodeTypes.PROMPT }?.params.orEmpty()
        val sampler = workflow.nodes.firstOrNull { it.type == DesktopNodeTypes.SD_SAMPLER }?.params.orEmpty()
        return prompt + sampler
    }

    private fun notifyChanged() {
        val current = synchronized(lock) { listeners.toList() }
        current.forEach { it() }
    }

    companion object {
        private const val STDERR_JOIN_MS = 5_000L
        private const val TIMEOUT_EXIT_CODE = -1
    }
}
