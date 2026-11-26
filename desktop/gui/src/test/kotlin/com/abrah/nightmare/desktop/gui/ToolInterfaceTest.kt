package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.desktop.graph.DesktopNodeTypes
import com.abrah.nightmare.desktop.graph.Workflow
import com.abrah.nightmare.desktop.graph.WorkflowPersistence
import com.abrah.nightmare.desktop.graph.WorkflowResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM seam for the external-tool command surface (ADR-0008): a future chat
 * effort drives the same 工作流 the default shell renders. Tests observe only
 * [ToolResult] plus the state snapshots — no UI test framework and no second
 * persistence format.
 */
class ToolInterfaceTest {

    @Test
    fun `a tool can start from an empty workflow, add nodes and set their parameters`() {
        val session = WorkflowSession()
        val state = DefaultModeState(session)
        val tools = ToolInterface(session, state)

        assertTrue(tools.dispatch(ToolCommand.NewWorkflow) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.AddNode("prompt", DesktopNodeTypes.PROMPT, 0f, 0f)) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.AddNode("sampler", DesktopNodeTypes.SD_SAMPLER, 260f, 0f)) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.SetParameter("prompt", "prompt", "a quiet mountain lake")) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.SetParameter("sampler", "steps", "12")) is ToolResult.Ok)

        val snapshot = state.snapshot()
        assertEquals(listOf("prompt", "sampler"), snapshot.chips.map { it.nodeId })
        assertEquals("a quiet mountain lake", snapshot.chips.first().summary)
        assertTrue(state.selectNode("sampler"))
        assertEquals("12", state.snapshot().fields.single { it.key == "steps" }.value)
    }

    @Test
    fun `unsafe duplicate and unknown node additions are rejected without changing the workflow`() {
        val session = WorkflowSession(Workflow())
        val state = DefaultModeState(session)
        val tools = ToolInterface(session, state)

        val unsafe = tools.dispatch(ToolCommand.AddNode("bad.id", DesktopNodeTypes.PROMPT))
        assertTrue(unsafe is ToolResult.Rejected)
        assertTrue((unsafe as ToolResult.Rejected).reason.contains("id"))
        assertEquals(emptyList<String>(), state.snapshot().chips.map { it.nodeId })

        assertTrue(tools.dispatch(ToolCommand.AddNode("prompt", DesktopNodeTypes.PROMPT)) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.AddNode("prompt", DesktopNodeTypes.OUTPUT)) is ToolResult.Rejected)
        assertTrue(tools.dispatch(ToolCommand.AddNode("mystery", "does.not.exist")) is ToolResult.Rejected)
        assertEquals(listOf("prompt"), state.snapshot().chips.map { it.nodeId })
    }

    @Test
    fun `a tool can wire nodes and illegal wires are rejected without changing the graph`() {
        val session = WorkflowSession(Workflow())
        val state = DefaultModeState(session)
        val tools = ToolInterface(session, state)
        listOf(
            "prompt" to DesktopNodeTypes.PROMPT,
            "sampler" to DesktopNodeTypes.SD_SAMPLER,
            "output" to DesktopNodeTypes.OUTPUT,
        ).forEach { (id, type) -> assertTrue(tools.dispatch(ToolCommand.AddNode(id, type)) is ToolResult.Ok) }

        assertTrue(tools.dispatch(ToolCommand.Connect("prompt", "sampler", "prompt")) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.Connect("sampler", "output", "image")) is ToolResult.Ok)
        val wired = session.workflow.connections
        assertEquals(2, wired.size)
        assertEquals("prompt", wired[0].from.node)
        assertEquals("sampler", wired[0].toNode)
        assertEquals("sampler", wired[1].from.node)
        assertEquals("output", wired[1].toNode)

        assertTrue(tools.dispatch(ToolCommand.Connect("prompt", "prompt", "prompt")) is ToolResult.Rejected)
        assertTrue(tools.dispatch(ToolCommand.Connect("ghost", "sampler", "prompt")) is ToolResult.Rejected)
        assertTrue(tools.dispatch(ToolCommand.Connect("output", "sampler", "prompt")) is ToolResult.Rejected)
        assertEquals(wired, session.workflow.connections)
    }

    @Test
    fun `setting an unknown parameter or node is rejected and leaves the workflow unchanged`() {
        val session = WorkflowSession(Workflow())
        val state = DefaultModeState(session)
        val tools = ToolInterface(session, state)
        assertTrue(tools.dispatch(ToolCommand.AddNode("prompt", DesktopNodeTypes.PROMPT)) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.SetParameter("prompt", "prompt", "dawn")) is ToolResult.Ok)

        val before = session.workflow
        assertTrue(tools.dispatch(ToolCommand.SetParameter("prompt", "not-a-widget", "x")) is ToolResult.Rejected)
        assertTrue(tools.dispatch(ToolCommand.SetParameter("ghost", "prompt", "x")) is ToolResult.Rejected)
        assertEquals(before, session.workflow)
    }

    @Test
    fun `a fake tool chain assembles a txt2img workflow from nothing and runs it through the real CLI seam`() {
        assumePosix()
        val (config, png) = fakeCliPng()
        val session = WorkflowSession(Workflow())
        val state = DefaultModeState(session, WorkflowRunner(config))
        val tools = ToolInterface(state)

        listOf("prompt", "sampler", "output").zip(
            listOf(DesktopNodeTypes.PROMPT, DesktopNodeTypes.SD_SAMPLER, DesktopNodeTypes.OUTPUT),
        ).forEach { (id, type) -> assertTrue(tools.dispatch(ToolCommand.AddNode(id, type)) is ToolResult.Ok) }
        assertTrue(tools.dispatch(ToolCommand.SetParameter("prompt", "prompt", "tool-built dawn")) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.SetParameter("sampler", "seed", "11")) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.Connect("prompt", "sampler", "prompt")) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.Connect("sampler", "output", "image")) is ToolResult.Ok)

        assertEquals(listOf("prompt", "sampler", "output"), state.snapshot().chips.map { it.nodeId })
        assertEquals(2, session.workflow.connections.size)

        assertTrue(tools.dispatch(ToolCommand.Run) is ToolResult.Ok)
        val run = state.runSnapshot()
        assertEquals(RunStatus.SUCCESS, run.status)
        assertEquals(1, run.progress.size)
        assertEquals(png.path, run.results.single().path)
        assertEquals("11", run.results.single().params["seed"])
        assertTrue("the tool-built prompt reached the CLI: ${run.command}", run.command.contains("tool-built dawn"))
    }

    @Test
    fun `a tool-built workflow uses the one persisted format with no parallel representation`() {
        val session = WorkflowSession(Workflow())
        val tools = ToolInterface(session, DefaultModeState(session))
        assertTrue(tools.dispatch(ToolCommand.AddNode("prompt", DesktopNodeTypes.PROMPT)) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.SetParameter("prompt", "prompt", "dawn")) is ToolResult.Ok)

        val file = Files.createTempFile("tool-interface-persist", ".json")
        val saved = WorkflowPersistence.save(session.workflow, file)
        assertTrue(saved is WorkflowResult.Ok)
        val loaded = WorkflowPersistence.load(file)
        assertTrue(loaded is WorkflowResult.Ok)
        assertEquals(session.workflow, (loaded as WorkflowResult.Ok).value)
    }

    @Test
    fun `a run command is rejected while a run is already in flight`() {
        val busy = StubRunControl(status = RunStatus.RUNNING)
        val rejected = ToolInterface(WorkflowSession(), busy).dispatch(ToolCommand.Run)
        assertTrue(rejected is ToolResult.Rejected)
        assertEquals(0, busy.triggerCalls)

        val refusing = StubRunControl(status = RunStatus.IDLE, acceptsRun = false)
        assertTrue(ToolInterface(WorkflowSession(), refusing).dispatch(ToolCommand.Run) is ToolResult.Rejected)
        assertEquals(1, refusing.triggerCalls)

        val ready = StubRunControl(status = RunStatus.IDLE)
        assertTrue(ToolInterface(WorkflowSession(), ready).dispatch(ToolCommand.Run) is ToolResult.Ok)
        assertEquals(1, ready.triggerCalls)
    }

    @Test
    fun `a failed run is rejected with the CLI reason instead of claiming an image`() {
        val failed = StubRunControl(
            statusAfter = RunStatus.FAILURE,
            error = "退出码 2：option '--scheduler' must be one of [discrete, karras]",
        )
        val result = ToolInterface(WorkflowSession(), failed).dispatch(ToolCommand.Run)

        assertTrue(result is ToolResult.Rejected)
        val reason = (result as ToolResult.Rejected).reason
        assertTrue("the failure reason must reach the caller: $reason", reason.contains("退出码 2"))
        assertTrue(reason.startsWith("运行失败："))
        assertEquals(1, failed.triggerCalls)
    }

    @Test
    fun `a failure without an error line still reports the first readable reason`() {
        val failed = StubRunControl(
            statusAfter = RunStatus.FAILURE,
            firstReason = "model not found",
        )
        val result = ToolInterface(WorkflowSession(), failed).dispatch(ToolCommand.Run)

        assertTrue(result is ToolResult.Rejected)
        assertTrue((result as ToolResult.Rejected).reason.contains("model not found"))
    }

    @Test
    fun `a cancelled run is rejected with the cancellation reason`() {
        val cancelled = StubRunControl(
            statusAfter = RunStatus.CANCELLED,
            firstReason = "已取消：子进程已结束",
        )
        val result = ToolInterface(WorkflowSession(), cancelled).dispatch(ToolCommand.Run)

        assertTrue(result is ToolResult.Rejected)
        assertTrue((result as ToolResult.Rejected).reason.contains("已取消"))
    }

    // ---- harness -----------------------------------------------------------

    /** [statusAfter] is the terminal snapshot a blocking triggerRun() leaves behind. */
    private class StubRunControl(
        status: RunStatus = RunStatus.IDLE,
        private val statusAfter: RunStatus = RunStatus.SUCCESS,
        private val acceptsRun: Boolean = true,
        private val error: String? = null,
        private val firstReason: String? = null,
    ) : WorkflowRunControl {
        private var currentStatus = status
        var triggerCalls: Int = 0
            private set

        override fun triggerRun(): Boolean {
            triggerCalls++
            if (acceptsRun) currentStatus = statusAfter
            return acceptsRun
        }

        override fun runSnapshot(): RunSnapshot = RunSnapshot(
            status = currentStatus,
            error = error,
            firstReason = firstReason,
        )
    }

    private fun assumePosix() {
        Assume.assumeFalse("fake CLI runner is a POSIX shell script", System.getProperty("os.name").startsWith("Windows"))
    }

    private fun fakeCliPng(): Pair<RunConfig, File> {
        val dir = Files.createTempDirectory("tool-interface-test").toFile()
        val png = File(dir, "tool-result.png")
        val argv = File(dir, "argv.txt")
        val script = File(dir, "fake-cli.sh")
        val quote = { s: String -> "'" + s.replace("'", "'\\''") + "'" }
        script.writeText(
            buildString {
                append("#!/bin/sh\n")
                append("printf '%s\\n' \"\$@\" > ${quote(argv.path)}\n")
                append("echo ${quote("[1/1] seed=11 -> ${png.path}")}\n")
                append(": > ${quote(png.path)}\n")
                append("exit 0\n")
            },
        )
        script.setExecutable(true)
        return RunConfig(cliExecutable = script.path, outputPath = png.path, timeoutMillis = 10_000L) to png
    }
}
