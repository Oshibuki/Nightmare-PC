package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.desktop.graph.Workflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end coverage of the GUI → CLI process seam. The fake CLI is a real
 * executable shell script: the runner must spawn it, read its stdout and turn
 * the observed lines into run state. No UI test framework is involved.
 */
class WorkflowRunnerTest {

    @Test
    fun `a successful run emits exactly one progress event per produced image`() {
        assumePosix()
        val run = runFake(imageNames = listOf("image-1.png", "image-2.png"))

        assertTrue(run.accepted)
        assertEquals(RunStatus.SUCCESS, run.snapshot.status)
        assertEquals(
            listOf(
                ProgressEvent(1, 2, 11L, File(run.dir, "image-1.png").path),
                ProgressEvent(2, 2, 12L, File(run.dir, "image-2.png").path),
            ),
            run.snapshot.progress,
        )
    }

    @Test
    fun `raw engine output from stdout and stderr reaches the same log beside progress events`() {
        assumePosix()
        val run = runFake(
            imageNames = listOf("image-1.png"),
            engineLines = listOf("engine: loading model"),
            stderrLines = listOf("engine: warning: low VRAM"),
        )

        assertEquals(1, run.snapshot.progress.size)
        assertTrue(run.snapshot.log.contains("engine: loading model"))
        assertTrue(run.snapshot.log.contains("engine: warning: low VRAM"))
        assertTrue(
            "the original progress line stays in the log too",
            run.snapshot.log.any { it.startsWith("[1/1] seed=11 -> ") },
        )
    }

    @Test
    fun `successful results carry each PNG path with its settings and exclude missing files`() {
        assumePosix()
        val workflow = workflowWith(samplerParams = mapOf("steps" to "12", "seed" to "11", "cfgScale" to "7.5"))
        val run = runFake(
            imageNames = listOf("real-1.png", "real-2.png"),
            missingImageNames = listOf("phantom.png"),
            workflow = workflow,
        )

        assertEquals(RunStatus.SUCCESS, run.snapshot.status)
        assertEquals(2, run.snapshot.results.size)
        assertEquals(
            listOf(File(run.dir, "real-1.png").path, File(run.dir, "real-2.png").path),
            run.snapshot.results.map { it.path },
        )
        assertEquals("12", run.snapshot.results.first().params["steps"])
        assertEquals("7.5", run.snapshot.results.first().params["cfgScale"])
        assertEquals("11", run.snapshot.results.first().params["seed"])
    }

    @Test
    fun `steps above the GUI cap reach the CLI and the result metadata as the capped value`() {
        assumePosix()
        val large = runFake(
            imageNames = listOf("capped.png"),
            workflow = workflowWith(samplerParams = mapOf("steps" to "50")),
        )

        assertEquals(RunStatus.SUCCESS, large.snapshot.status)
        assertEquals("20", large.argvValue("--steps"))
        assertEquals("20", large.snapshot.results.single().params["steps"])

        val ordinary = runFake(
            imageNames = listOf("ordinary.png"),
            workflow = workflowWith(samplerParams = mapOf("steps" to "12")),
        )
        assertEquals("12", ordinary.argvValue("--steps"))
        assertEquals("12", ordinary.snapshot.results.single().params["steps"])
    }

    @Test
    fun `an unparseable steps value is passed to the CLI instead of being silently capped`() {
        assumePosix()
        val run = runFake(
            imageNames = listOf("unchanged.png"),
            workflow = workflowWith(samplerParams = mapOf("steps" to "many")),
        )

        assertEquals("many", run.argvValue("--steps"))
        assertEquals("many", run.snapshot.results.single().params["steps"])
    }

    @Test
    fun `the workflow is projected into a stateless CLI command`() {
        assumePosix()
        val run = runFake(
            imageNames = listOf("image-1.png"),
            workflow = workflowWith(
                samplerParams = mapOf(
                    "sampler" to "ddim",
                    "scheduler" to "karras",
                    "steps" to "9",
                    "cfgScale" to "6.5",
                    "seed" to "77",
                    "width" to "768",
                    "height" to "512",
                ),
                promptParams = mapOf("prompt" to "moonlit harbor", "negative" to "blurry"),
            ),
        )

        assertEquals("txt2img", run.argv.first())
        assertTrue(run.snapshot.command.first().endsWith("fake-cli.sh"))
        assertEquals(run.argv, run.snapshot.command.drop(1))
        assertTrue(
            "every edited workflow value reached the child argv: ${run.argv}",
            run.argv.containsAll(
                listOf(
                    "--model", "/models/sd15.safetensors",
                    "--prompt", "moonlit harbor",
                    "--negative-prompt", "blurry",
                    "--sampler", "ddim",
                    "--scheduler", "karras",
                    "--steps", "9",
                    "--cfg-scale", "6.5",
                    "--seed", "77",
                    "--width", "768",
                    "--height", "512",
                    "--out", File(run.dir, "image-1.png").path,
                    "--batch", "1",
                ),
            ),
        )
    }

    @Test
    fun `a legacy normal scheduler is projected as discrete without editing the workflow`() {
        assumePosix()
        val workflow = workflowWith(samplerParams = mapOf("scheduler" to "normal"))
        val run = runFake(imageNames = listOf("image-1.png"), workflow = workflow)

        assertEquals(RunStatus.SUCCESS, run.snapshot.status)
        assertEquals("discrete", run.argvValue("--scheduler"))
        assertEquals("normal", workflow.nodes.first { it.id == "sampler" }.params["scheduler"])
    }

    @Test
    fun `display names become engine values while unknown values reach the CLI unchanged`() {
        assumePosix()
        val named = runFake(
            imageNames = listOf("image-1.png"),
            workflow = workflowWith(
                samplerParams = mapOf("sampler" to "DPM++ 2M", "scheduler" to "Karras"),
            ),
        )
        assertEquals(RunStatus.SUCCESS, named.snapshot.status)
        assertEquals("dpm++2m", named.argvValue("--sampler"))
        assertEquals("karras", named.argvValue("--scheduler"))

        val upper = runFake(
            imageNames = listOf("image-2.png"),
            workflow = workflowWith(samplerParams = mapOf("scheduler" to "NORMAL")),
        )
        assertEquals("discrete", upper.argvValue("--scheduler"))

        val unknown = runFake(
            imageNames = listOf("image-3.png"),
            workflow = workflowWith(
                samplerParams = mapOf("sampler" to "Mystery", "scheduler" to "Turbo"),
            ),
        )
        assertEquals("Mystery", unknown.argvValue("--sampler"))
        assertEquals("Turbo", unknown.argvValue("--scheduler"))
    }

    @Test
    fun `a run started while another is active is rejected without changing the run state`() {
        assumePosix()
        val fake = fakeCli(imageNames = listOf("image-1.png"), sleepSeconds = 1)
        val runner = WorkflowRunner(fake.config)
        val reachedRunning = CountDownLatch(1)
        runner.addChangeListener { if (runner.isRunning()) reachedRunning.countDown() }

        val worker = Thread { runner.run(builtInTxt2imgWorkflow()) }.apply { isDaemon = true }
        worker.start()
        try {
            assertTrue("the first run should become observable as RUNNING", reachedRunning.await(5, TimeUnit.SECONDS))
            assertFalse("a second concurrent run must be rejected", runner.run(builtInTxt2imgWorkflow()))
            assertEquals(RunStatus.RUNNING, runner.snapshot().status)
        } finally {
            worker.join(10_000)
        }
        assertEquals(RunStatus.SUCCESS, runner.snapshot().status)
    }

    @Test
    fun `default mode state exposes runs of the shared workflow for shells and tools`() {
        assumePosix()
        val fake = fakeCli(imageNames = listOf("result.png"))
        val state = DefaultModeState(runner = WorkflowRunner(fake.config))
        var changes = 0
        val unsubscribe = state.addChangeListener { changes++ }

        assertTrue(state.selectNode("sampler"))
        assertTrue(state.setSelectedParameter("steps", "7"))
        assertTrue(state.triggerRun())

        val snapshot = state.runSnapshot()
        assertEquals(RunStatus.SUCCESS, snapshot.status)
        assertEquals(1, snapshot.results.size)
        assertEquals(File(fake.dir, "result.png").path, snapshot.results.single().path)
        assertEquals("7", snapshot.results.single().params["steps"])
        assertEquals(fake.argvFile.readLines(), snapshot.command.drop(1))
        assertTrue("listeners should observe the run state changes", changes > 0)
        unsubscribe()
    }

    @Test
    fun `a non-zero exit becomes failure with exit code and first stderr reason`() {
        assumePosix()
        val run = runFake(
            imageNames = listOf("partial.png"),
            stderrLines = listOf("error: CUDA out of memory", "detail: tried to allocate 8GiB"),
            exitCode = 3,
        )

        assertEquals(RunStatus.FAILURE, run.snapshot.status)
        assertEquals(FailureKind.EXIT_CODE, run.snapshot.failureKind)
        assertEquals(3, run.snapshot.exitCode)
        assertEquals("error: CUDA out of memory", run.snapshot.firstReason)
        assertTrue(run.snapshot.error.orEmpty().contains("退出码 3"))
        assertTrue(run.snapshot.error.orEmpty().contains("CUDA out of memory"))
        assertTrue("full stderr must stay in the log", run.snapshot.log.contains("detail: tried to allocate 8GiB"))
        assertTrue("no half-finished PNG in the result panel", run.snapshot.results.isEmpty())
        assertFalse("temporary output from a failed run must be deleted", File(run.dir, "partial.png").exists())
    }

    @Test
    fun `stderr error on a zero exit still becomes a failure`() {
        assumePosix()
        val run = runFake(
            imageNames = listOf("partial.png"),
            stderrLines = listOf("error: failed to write latent"),
            exitCode = 0,
        )

        assertEquals(RunStatus.FAILURE, run.snapshot.status)
        assertEquals(FailureKind.STDERR, run.snapshot.failureKind)
        assertTrue(run.snapshot.error.orEmpty().contains("failed to write latent"))
        assertTrue(run.snapshot.results.isEmpty())
        assertFalse(File(run.dir, "partial.png").exists())
    }

    @Test
    fun `a missing model becomes a readable failure instead of a silent one`() {
        assumePosix()
        val run = runFake(
            imageNames = emptyList(),
            stderrLines = listOf("error: model not found: /models/absent.safetensors"),
            exitCode = 1,
        )

        assertEquals(RunStatus.FAILURE, run.snapshot.status)
        assertEquals(FailureKind.MISSING_MODEL, run.snapshot.failureKind)
        assertTrue(run.snapshot.error.orEmpty().contains("model not found"))
        assertTrue("the whole stderr line reaches the log", run.snapshot.log.contains("error: model not found: /models/absent.safetensors"))
    }

    @Test
    fun `a timeout stops the child, fails the run and removes partial output`() {
        assumePosix()
        val run = runFake(
            imageNames = listOf("slow.png"),
            sleepSeconds = 3,
            timeoutMillis = 400L,
        )

        assertEquals(RunStatus.FAILURE, run.snapshot.status)
        assertEquals(FailureKind.TIMEOUT, run.snapshot.failureKind)
        assertTrue(run.snapshot.error.orEmpty().contains("超时"))
        assertTrue(run.snapshot.results.isEmpty())
        assertFalse(File(run.dir, "slow.png").exists())
    }

    @Test
    fun `the GUI timeout setting bounds a run configured with the default timeout`() {
        assumePosix()
        val fake = fakeCli(imageNames = listOf("slow.png"), sleepSeconds = 3)
        val config = fake.config.copy(timeoutMillis = GuiRunSettings.DEFAULT_TIMEOUT_MILLIS)
        val previous = GuiRunSettings.timeoutMillis
        try {
            GuiRunSettings.timeoutMillis = 400L
            val runner = WorkflowRunner(config)
            assertTrue(runner.run(builtInTxt2imgWorkflow()))
            assertEquals(RunStatus.FAILURE, runner.snapshot().status)
            assertEquals(FailureKind.TIMEOUT, runner.snapshot().failureKind)
        } finally {
            GuiRunSettings.timeoutMillis = previous
        }
    }

    @Test
    fun `cancelling an active run kills the child and leaves no results`() {
        assumePosix()
        val fake = fakeCli(
            imageNames = listOf("cancelled.png"),
            sleepSeconds = 5,
            earlyLines = listOf("engine: started"),
        )
        val runner = WorkflowRunner(fake.config)
        val handleReady = CountDownLatch(1)
        val unsubscribe = runner.addChangeListener {
            if (runner.processHandle() != null) handleReady.countDown()
        }
        val worker = Thread { runner.run(builtInTxt2imgWorkflow()) }.apply { isDaemon = true }
        worker.start()
        var handle: Process? = null
        try {
            assertTrue("child handle should become visible", handleReady.await(5, TimeUnit.SECONDS))
            handle = runner.processHandle()
            assertTrue("runner must be running before cancel", runner.isRunning())
            assertTrue("cancel should be accepted", WorkflowRunner.cancelActiveProcess(handle))
        } finally {
            worker.join(10_000)
            unsubscribe()
        }
        assertEquals(RunStatus.CANCELLED, runner.snapshot().status)
        assertFalse("cancelled child must not survive", handle!!.isAlive)
        assertTrue(runner.snapshot().results.isEmpty())
        assertFalse("partial output must be deleted", File(fake.dir, "cancelled.png").exists())
    }

    @Test
    fun `window close cleanup ends a running child through the exposed handle`() {
        assumePosix()
        val fake = fakeCli(
            imageNames = listOf("closing.png"),
            sleepSeconds = 5,
            earlyLines = listOf("engine: started"),
        )
        val state = DefaultModeState(runner = WorkflowRunner(fake.config))
        val handleReady = CountDownLatch(1)
        val unsubscribe = state.addChangeListener {
            if (state.runHandle() != null) handleReady.countDown()
        }
        val worker = Thread { state.triggerRun() }.apply { isDaemon = true }
        worker.start()
        var handle: Process? = null
        try {
            assertTrue(handleReady.await(5, TimeUnit.SECONDS))
            handle = state.runHandle()
            assertTrue("closing the window destroys the live child", WorkflowRunner.cancelActiveProcess(handle))
        } finally {
            worker.join(10_000)
            unsubscribe()
        }
        assertEquals(RunStatus.CANCELLED, state.runSnapshot().status)
        assertFalse(handle!!.isAlive)
        assertTrue(state.runSnapshot().results.isEmpty())
    }

    @Test
    fun `a failed run leaves the workflow editable and rerunnable`() {
        assumePosix()
        val control = File(Files.createTempDirectory("gui-rerun-test").toFile(), "fail.flag")
        control.writeText("fail")
        val fake = fakeCli(
            imageNames = listOf("retry.png"),
            prelude = "if [ -f '${control.path}' ]; then echo 'error: simulated failure' >&2; exit 2; fi\n",
        )
        val state = DefaultModeState(runner = WorkflowRunner(fake.config))

        assertTrue(state.triggerRun())
        assertEquals(RunStatus.FAILURE, state.runSnapshot().status)
        val before = state.workflow
        assertEquals(before, state.workflow)
        assertTrue("parameters stay editable after failure", state.selectNode("sampler"))
        assertTrue(state.setSelectedParameter("steps", "4"))

        assertTrue("a failed run must not lock out reruns", control.delete())
        assertTrue(state.triggerRun())
        assertEquals(RunStatus.SUCCESS, state.runSnapshot().status)
        assertEquals("4", state.runSnapshot().results.single().params["steps"])
    }

    // ---- fake CLI harness ---------------------------------------------------

    private data class FakeCli(
        val config: RunConfig,
        val dir: File,
        val outputs: List<File>,
        val argvFile: File,
    )

    private data class FakeRun(
        val accepted: Boolean,
        val snapshot: RunSnapshot,
        val argv: List<String>,
        val dir: File,
        val outputs: List<File>,
    )

    private fun FakeRun.argvValue(flag: String): String? {
        val index = argv.indexOf(flag)
        return if (index < 0) null else argv.getOrNull(index + 1)
    }

    private fun assumePosix() {
        Assume.assumeFalse(
            "fake CLI runner is a POSIX shell script",
            System.getProperty("os.name").startsWith("Windows"),
        )
    }

    private fun fakeCli(
        imageNames: List<String>,
        missingImageNames: List<String> = emptyList(),
        seeds: List<Long> = (imageNames + missingImageNames).indices.map { 11L + it },
        engineLines: List<String> = listOf("engine: loading model", "engine: sampling finished"),
        stderrLines: List<String> = emptyList(),
        exitCode: Int = 0,
        sleepSeconds: Int = 0,
        earlyLines: List<String> = emptyList(),
        prelude: String = "",
        envExtra: Map<String, String> = emptyMap(),
        timeoutMillis: Long = 10_000L,
    ): FakeCli {
        val dir = Files.createTempDirectory("gui-runner-test").toFile()
        val allNames = imageNames + missingImageNames
        val outputs = allNames.map { File(dir, it) }
        val argvFile = File(dir, "argv.txt")
        val script = File(dir, "fake-cli.sh")
        val quote = { s: String -> "'" + s.replace("'", "'\\''") + "'" }

        val body = buildString {
            append("#!/bin/sh\n")
            append("printf '%s\\n' \"\$@\" > ${quote(argvFile.path)}\n")
            append(prelude)
            earlyLines.forEach { append("echo ${quote(it)}\n") }
            if (sleepSeconds > 0) append("sleep $sleepSeconds\n")
            outputs.forEachIndexed { i, output ->
                append("echo ${quote("[${i + 1}/${outputs.size}] seed=${seeds[i]} -> ${output.path}")}\n")
                engineLines.getOrNull(i)?.let { append("echo ${quote(it)}\n") }
                if (i < imageNames.size) append(": > ${quote(output.path)}\n")
            }
            engineLines.drop(outputs.size).forEach { append("echo ${quote(it)}\n") }
            stderrLines.forEach { append("echo ${quote(it)} >&2\n") }
            append("exit $exitCode\n")
        }
        script.writeText(body)
        script.setExecutable(true)

        val config = RunConfig(
            cliExecutable = script.path,
            modelPath = "/models/sd15.safetensors",
            outputPath = outputs.firstOrNull()?.path ?: File(dir, "result.png").path,
            batch = outputs.size,
            timeoutMillis = timeoutMillis,
            env = mapOf("FAKE_ARGV" to argvFile.path) + envExtra,
        )
        return FakeCli(config, dir, outputs, argvFile)
    }

    private fun runFake(
        imageNames: List<String>,
        missingImageNames: List<String> = emptyList(),
        seeds: List<Long> = (imageNames + missingImageNames).indices.map { 11L + it },
        engineLines: List<String> = listOf("engine: loading model", "engine: sampling finished"),
        stderrLines: List<String> = emptyList(),
        exitCode: Int = 0,
        sleepSeconds: Int = 0,
        envExtra: Map<String, String> = emptyMap(),
        workflow: Workflow = builtInTxt2imgWorkflow(),
        timeoutMillis: Long = 10_000L,
    ): FakeRun {
        val fake = fakeCli(
            imageNames = imageNames,
            missingImageNames = missingImageNames,
            seeds = seeds,
            engineLines = engineLines,
            stderrLines = stderrLines,
            exitCode = exitCode,
            sleepSeconds = sleepSeconds,
            envExtra = envExtra,
            timeoutMillis = timeoutMillis,
        )
        val runner = WorkflowRunner(fake.config)
        val accepted = runner.run(workflow)
        val argv = if (fake.argvFile.isFile) fake.argvFile.readLines() else emptyList()
        return FakeRun(accepted, runner.snapshot(), argv, fake.dir, fake.outputs.take(imageNames.size))
    }

    private fun workflowWith(
        samplerParams: Map<String, String> = emptyMap(),
        promptParams: Map<String, String> = emptyMap(),
    ): Workflow {
        val base = builtInTxt2imgWorkflow()
        return base.copy(
            nodes = base.nodes.map { node ->
                when (node.id) {
                    "sampler" -> node.copy(params = node.params + samplerParams)
                    "prompt" -> node.copy(params = node.params + promptParams)
                    else -> node
                }
            },
        )
    }
}
