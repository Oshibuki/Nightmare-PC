package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.desktop.core.SamplerWire
import com.abrah.nightmare.desktop.graph.DesktopNodeTypes
import com.abrah.nightmare.desktop.graph.Workflow
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The one GUI-wide 出图步数上限, shared by edits, prompts and the CLI projection. */
const val MAX_RUN_STEPS = 20

/**
 * Clamp only a parseable integer above [MAX_RUN_STEPS]. Values at or below the
 * cap — and anything unparseable — stay untouched: the CLI owns the error for
 * bad flags, and the GUI must not silently swallow them.
 */
private fun parsedSteps(value: String): Int? = value.trim().toIntOrNull()

private fun cappedSteps(value: String): String {
    val parsed = parsedSteps(value) ?: return value
    return if (parsed > MAX_RUN_STEPS) MAX_RUN_STEPS.toString() else value
}

/** Null means [value] is a legal `steps` edit; otherwise the tool/UI rejection reason. */
internal fun stepsRejectionReason(value: String): String? {
    val parsed = parsedSteps(value) ?: return "steps 必须是整数"
    return if (parsed > MAX_RUN_STEPS) "steps 最大 $MAX_RUN_STEPS" else null
}

/**
 * Drives the stateless CLI as a child process and owns its handle (ADR-0009).
 * The GUI projects the workflow into arguments here; it never calls `Cli.run`
 * or any internal generation function in-process. Failure, cancellation and
 * timeout all settle into one observable [RunSnapshot] (ADR-0010).
 */
class WorkflowRunner(private val config: RunConfig = RunConfig()) {

    private val lock = Any()
    private val listeners = mutableListOf<() -> Unit>()
    @Volatile private var currentSnapshot = RunSnapshot()
    @Volatile private var process: Process? = null
    @Volatile private var cancellationRequested = false

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
     * Ask the active run to stop. The stdout drain then ends, finalization sets
     * CANCELLED and removes any partial output. Returns false when idle.
     */
    fun cancel(): Boolean {
        val child = synchronized(lock) {
            if (currentSnapshot.status != RunStatus.RUNNING) return false
            cancellationRequested = true
            process
        }
        child?.destroyForcibly()
        notifyChanged()
        return child != null
    }

    /**
     * Starts one run and blocks until the child exits. Returns false when a run
     * is already in flight, so the shell can disable 「运行」 while busy.
     */
    fun run(workflow: Workflow): Boolean {
        synchronized(lock) {
            if (currentSnapshot.status == RunStatus.RUNNING) return false
            cancellationRequested = false
            currentSnapshot = RunSnapshot(status = RunStatus.RUNNING, command = project(workflow))
        }
        val runParams = paramsFor(workflow)
        notifyChanged()

        val builder = ProcessBuilder(currentSnapshot.command)
        config.env.forEach { (key, value) -> builder.environment()[key] = value }
        // Snapshot before spawning: a fast fake CLI can create its output in
        // the time between start() and the next line of this method.
        val preExisting = preExistingOutputPaths()
        val child = try {
            builder.start()
        } catch (ex: java.io.IOException) {
            val message = "cannot start CLI '${config.cliExecutable}': ${ex.message}"
            synchronized(lock) {
                currentSnapshot = currentSnapshot.copy(
                    status = RunStatus.FAILURE,
                    error = message,
                    log = listOf(message),
                    failureKind = FailureKind.EXIT_CODE,
                    firstReason = message,
                )
                process = null
            }
            notifyChanged()
            return true
        }
        synchronized(lock) {
            process = child
            liveByProcess[child] = this
        }
        // Closing the window exits the JVM; the hook is the last line of
        // defence when the shell did not get a chance to dispose its effect.
        val shutdownHook = Thread { child.destroyForcibly() }.apply {
            isDaemon = true
            name = "gui-cli-shutdown"
        }
        addShutdownHookSafely(shutdownHook)

        val timeoutMillis = effectiveTimeout()
        val timedOut = AtomicBoolean(false)
        val watchdog = Thread {
            if (!child.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                timedOut.set(true)
                child.destroyForcibly()
            }
        }.apply {
            isDaemon = true
            name = "gui-cli-watchdog"
        }
        watchdog.start()

        // One log panel for every raw line, from both streams. Progress lines
        // are parsed as well as logged -- the two views are not alternatives.
        val logLines = Collections.synchronizedList(mutableListOf<String>())
        val stderrLines = Collections.synchronizedList(mutableListOf<String>())
        fun appendLog(line: String, fromStderr: Boolean = false) {
            logLines += line
            if (fromStderr) stderrLines += line
            val snapshotLog = synchronized(logLines) { logLines.toList() }
            synchronized(lock) {
                currentSnapshot = currentSnapshot.copy(log = snapshotLog)
            }
            notifyChanged()
        }

        // Drain stderr on its own thread so a chatty failure cannot fill the
        // pipe and stall the child while stdout is still being consumed.
        val stderrDrain = Thread {
            child.errorStream.bufferedReader().useLines { lines -> lines.forEach { appendLog(it, true) } }
        }.apply {
            isDaemon = true
            name = "gui-cli-stderr"
        }
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
        val exitCode = settleExitCode(child)

        val finalLog = synchronized(logLines) { logLines.toList() }
        val finalStderr = synchronized(stderrLines) { stderrLines.toList() }
        val didTimeOut = timedOut.get()
        val cancelled = synchronized(lock) { cancellationRequested }
        val outcome = classify(didTimeOut, cancelled, exitCode, finalStderr, finalLog, timeoutMillis)

        // A progress line is a promise, not a PNG. Only files that really exist
        // after a clean exit are offered to the result panel; failed and
        // cancelled runs drop every artifact this run created.
        val results = if (outcome.status == RunStatus.SUCCESS) {
            events.filter { File(it.path).isFile }.map { event ->
                ResultArtifact(event.path, runParams + ("seed" to event.seed.toString()))
            }
        } else {
            deleteUnfinishedArtifacts(events.map { it.path }, preExisting)
            emptyList()
        }
        val finalError = when (outcome.status) {
            RunStatus.FAILURE -> "退出码 $exitCode：${outcome.reason}"
            RunStatus.CANCELLED -> outcome.reason
            else -> null
        }
        synchronized(lock) {
            currentSnapshot = currentSnapshot.copy(
                status = outcome.status,
                error = finalError,
                log = finalLog,
                results = results,
                failureKind = outcome.failureKind,
                exitCode = if (outcome.status == RunStatus.FAILURE) exitCode else null,
                firstReason = if (outcome.status == RunStatus.FAILURE) outcome.reason else null,
            )
            process = null
            liveByProcess.remove(child)
        }
        removeShutdownHookSafely(shutdownHook)
        notifyChanged()
        return true
    }

    /**
     * Workflow → CLI arguments. The CLI keeps no state between invocations.
     * An image input node selects `img2img` (ADR-0013): the same single child
     * process, with `--init-img`/`--strength` from that node and no `--batch`,
     * which the img2img subcommand does not accept. Without such a node the
     * txt2img projection is unchanged.
     */
    private fun project(workflow: Workflow): List<String> {
        val imageNode = workflow.nodes.firstOrNull { it.type == DesktopNodeTypes.IMAGE_INPUT }
        val args = mutableListOf(
            config.cliExecutable,
            if (imageNode == null) "txt2img" else "img2img",
        )
        config.modelPath?.takeIf { it.isNotBlank() }?.let { args += listOf("--model", it) }
        val promptNode = workflow.nodes.firstOrNull { it.type == DesktopNodeTypes.PROMPT }
        val samplerNode = workflow.nodes.firstOrNull { it.type == DesktopNodeTypes.SD_SAMPLER }
        promptNode?.params?.get("prompt")?.let { args += listOf("--prompt", it) }
        promptNode?.params?.get("negative")?.takeIf { it.isNotBlank() }
            ?.let { args += listOf("--negative-prompt", it) }
        samplerNode?.params?.let { params ->
            listOf(
                "--sampler" to params["sampler"]?.let(::engineSampler),
                "--scheduler" to params["scheduler"]?.let(::engineScheduler),
                "--steps" to params["steps"]?.let(::cappedSteps),
                "--cfg-scale" to params["cfgScale"],
                "--seed" to params["seed"],
                "--width" to params["width"],
                "--height" to params["height"],
            ).forEach { (flag, value) ->
                if (!value.isNullOrBlank()) args += listOf(flag, value)
            }
        }
        args += listOf("--out", config.outputPath)
        if (imageNode == null) {
            args += listOf("--batch", config.batch.toString())
        } else {
            args += listOf("--init-img", imageNode.params["path"] ?: "")
            // A written strength is passed through untouched: the CLI owns the
            // 0..1 usage check (ADR-0013), so the GUI must not silently clamp it.
            args += listOf(
                "--strength",
                imageNode.params["strength"]?.takeIf { it.isNotBlank() }
                    ?: DesktopNodeTypes.DEFAULT_IMG2IMG_STRENGTH,
            )
        }
        return args
    }

    /**
     * Only the CLI projection and the result metadata are normalized: the
     * persisted/workflow value keeps exactly what the user or a tool wrote.
     * Unknown values pass through so the CLI can report the bad flag; the GUI
     * must not silently swallow them.
     */
    private fun engineSampler(value: String): String =
        engineValue(value, SamplerWire.SAMPLERS, SamplerWire::samplerLabel)

    private fun engineScheduler(value: String): String {
        // The engine aliases "normal" to "discrete", but this CLI's argv
        // allowlist rejects the alias, so it needs the canonical word.
        if (value.equals("normal", ignoreCase = true)) return SamplerWire.DEFAULT_SCHEDULER
        return engineValue(value, SamplerWire.SCHEDULERS, SamplerWire::schedulerLabel)
    }

    private fun engineValue(value: String, legal: List<String>, label: (String) -> String): String {
        if (value in legal) return value
        val trimmed = value.trim()
        if (trimmed in legal) return trimmed
        return legal.firstOrNull { label(it).equals(trimmed, ignoreCase = true) } ?: value
    }

    /** The workflow settings that produced this run, attached to each result. */
    private fun paramsFor(workflow: Workflow): Map<String, String> {
        val prompt = workflow.nodes.firstOrNull { it.type == DesktopNodeTypes.PROMPT }?.params.orEmpty()
        val sampler = workflow.nodes.firstOrNull { it.type == DesktopNodeTypes.SD_SAMPLER }?.params.orEmpty()
        // PNG metadata must describe the run that actually happened, not the
        // uncapped value someone typed or loaded from an older workflow.
        val steps = sampler["steps"]?.let(::cappedSteps)
        return if (steps == null) prompt + sampler else (prompt + sampler) + ("steps" to steps)
    }

    /** An explicit config timeout wins; otherwise the shell's field decides. */
    private fun effectiveTimeout(): Long = if (config.timeoutMillis == GuiRunSettings.DEFAULT_TIMEOUT_MILLIS) {
        GuiRunSettings.timeoutMillis
    } else {
        config.timeoutMillis
    }

    private fun settleExitCode(child: Process): Int {
        if (child.isAlive) {
            child.destroyForcibly()
            child.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
        }
        return if (child.isAlive) TIMEOUT_EXIT_CODE else child.exitValue()
    }

    private fun classify(
        didTimeOut: Boolean,
        cancelled: Boolean,
        exitCode: Int,
        stderrLines: List<String>,
        stdoutLines: List<String>,
        timeoutMillis: Long,
    ): Outcome {
        if (cancelled) return Outcome(RunStatus.CANCELLED, null, "已取消：子进程已结束")
        if (didTimeOut) {
            return Outcome(RunStatus.FAILURE, FailureKind.TIMEOUT, "运行超时（$timeoutMillis ms），子进程已结束")
        }
        val allLines = stderrLines + stdoutLines
        allLines.firstOrNull { MISSING_MODEL_REGEX.containsMatchIn(it) }?.let { line ->
            return Outcome(RunStatus.FAILURE, FailureKind.MISSING_MODEL, line)
        }
        if (exitCode != 0) {
            val reason = stderrLines.firstOrNull { it.isNotBlank() }
                ?: stdoutLines.firstOrNull { it.isNotBlank() && ProgressLines.parse(it) == null }
                ?: "CLI 退出"
            return Outcome(RunStatus.FAILURE, FailureKind.EXIT_CODE, reason)
        }
        stderrLines.firstOrNull { STDERR_ERROR_REGEX.containsMatchIn(it) }?.let { line ->
            return Outcome(RunStatus.FAILURE, FailureKind.STDERR, line)
        }
        return Outcome(RunStatus.SUCCESS, null, null)
    }

    private fun preExistingOutputPaths(): Set<String> =
        File(config.outputPath).absoluteFile.parentFile?.listFiles().orEmpty()
            .map { it.absolutePath }.toSet()

    /** Delete only files this run created; never touch a user's earlier PNG. */
    private fun deleteUnfinishedArtifacts(paths: List<String>, preExisting: Set<String>) {
        (paths + config.outputPath).map { File(it).absolutePath }.distinct().forEach { path ->
            if (path !in preExisting) File(path).delete()
        }
        val dir = File(config.outputPath).absoluteFile.parentFile ?: return
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".tmp") && it.absolutePath !in preExisting }
            ?.forEach { it.delete() }
    }

    private fun notifyChanged() {
        val current = synchronized(lock) { listeners.toList() }
        current.forEach { it() }
    }

    private data class Outcome(
        val status: RunStatus,
        val failureKind: FailureKind?,
        val reason: String?,
    )

    companion object {
        private const val STDERR_JOIN_MS = 5_000L
        private const val EXIT_WAIT_MS = 2_000L
        private const val TIMEOUT_EXIT_CODE = -1
        private val STDERR_ERROR_REGEX = Regex("""(?i)\b(error|failed|failure|exception|cannot|unable)\b""")
        private val MISSING_MODEL_REGEX = Regex(
            """(?i)(model.{0,40}(not found|missing|cannot find|no such file|does not exist)|(not found|missing|cannot find).{0,40}model|failed to load model)""",
        )
        private val liveByProcess = Collections.synchronizedMap(IdentityHashMap<Process, WorkflowRunner>())

        /**
         * The shell calls this for both the cancel button and window close: it
         * routes through the owning runner so the run becomes CANCELLED, and
         * still destroys an unknown process as a fallback.
         */
        @JvmStatic
        fun cancelActiveProcess(process: Process?): Boolean {
            if (process == null) return false
            val runner = synchronized(liveByProcess) { liveByProcess[process] }
            if (runner != null) return runner.cancel()
            if (process.isAlive) process.destroyForcibly()
            return false
        }

        private fun addShutdownHookSafely(hook: Thread) {
            try {
                Runtime.getRuntime().addShutdownHook(hook)
            } catch (_: IllegalStateException) {
                // JVM already shutting down; clean up now instead.
                hook.run()
            }
        }

        private fun removeShutdownHookSafely(hook: Thread) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook)
            } catch (_: IllegalStateException) {
                // Shutdown in progress: the hook itself will run shortly.
            }
        }
    }
}
