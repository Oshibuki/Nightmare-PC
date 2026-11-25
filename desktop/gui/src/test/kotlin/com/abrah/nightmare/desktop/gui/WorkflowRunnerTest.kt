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
