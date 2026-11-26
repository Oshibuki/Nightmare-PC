package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.desktop.canvas.Pt
import com.abrah.nightmare.desktop.graph.DesktopNodeTypes
import com.abrah.nightmare.desktop.graph.Position
import com.abrah.nightmare.desktop.graph.Source
import com.abrah.nightmare.desktop.graph.Workflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * External behaviour of the advanced shell: its canvas edits the one shared
 * workflow, its wire gestures report refusals, and its run view is the same
 * 运行态 the default shell reads. No UI test framework is involved.
 */
class AdvancedModeStateTest {

    @Test
    fun `advanced mode renders the shared built-in workflow as positioned nodes and wires`() {
        val state = AdvancedModeState(WorkflowSession())
        val snapshot = state.snapshot()

        assertEquals(listOf("prompt", "sampler", "output"), snapshot.nodes.map { it.id })
        assertEquals(
            listOf(DesktopNodeTypes.PROMPT, DesktopNodeTypes.SD_SAMPLER, DesktopNodeTypes.OUTPUT),
            snapshot.nodes.map { it.type },
        )
        assertEquals(2, snapshot.wires.size)
        assertEquals("prompt", snapshot.wires[0].fromNode)
        assertEquals("prompt", snapshot.wires[0].fromPort)
        assertEquals("sampler", snapshot.wires[0].toNode)
        assertEquals("prompt", snapshot.wires[0].toPort)
        assertEquals("sampler", snapshot.wires[1].fromNode)
        assertEquals("output", snapshot.wires[1].toNode)

        assertTrue(snapshot.nodes[0].ports.any { !it.isInput && it.name == "prompt" })
        assertTrue(snapshot.nodes[1].ports.any { it.isInput && it.name == "prompt" })
        assertTrue(snapshot.nodes[1].ports.any { !it.isInput && it.name == "image" })
        assertTrue(snapshot.nodes[2].ports.any { it.isInput && it.name == "image" })
        assertEquals("prompt", snapshot.selectedNodeId)
    }

    @Test
    fun `palette node types add to the one workflow with their defaults`() {
        val session = WorkflowSession(Workflow())
        val state = AdvancedModeState(session)

        assertTrue(state.addNode(DesktopNodeTypes.PROMPT))
        assertTrue(state.addNode(DesktopNodeTypes.SD_SAMPLER))
        assertTrue(state.addNode(DesktopNodeTypes.OUTPUT))
        assertFalse(state.addNode("video.unknown"))

        assertEquals(
            listOf(DesktopNodeTypes.PROMPT, DesktopNodeTypes.SD_SAMPLER, DesktopNodeTypes.OUTPUT),
            session.workflow.nodes.map { it.type },
        )
        assertEquals(session.workflow.nodes.map { it.id }, state.snapshot().nodes.map { it.id })
        val sampler = session.workflow.nodes.single { it.type == DesktopNodeTypes.SD_SAMPLER }
        assertEquals("20", sampler.params["steps"])
        assertEquals("true", session.workflow.nodes.single { it.type == DesktopNodeTypes.OUTPUT }.params["save"])
    }

    @Test
    fun `advanced palette offers the image input node alongside the other three types`() {
        assertEquals(
            listOf(
                DesktopNodeTypes.PROMPT,
                DesktopNodeTypes.SD_SAMPLER,
                DesktopNodeTypes.OUTPUT,
                DesktopNodeTypes.IMAGE_INPUT,
            ),
            advancedPaletteTypes().map { it.first },
        )
        assertEquals("图片输入", advancedPaletteTypes().single { it.first == DesktopNodeTypes.IMAGE_INPUT }.second)
    }

    @Test
    fun `an image input added from the palette exposes editable path and strength on the canvas`() {
        val session = WorkflowSession(Workflow())
        val state = AdvancedModeState(session)

        assertTrue(state.addNode(DesktopNodeTypes.IMAGE_INPUT))
        val node = session.workflow.nodes.single()
        assertEquals(DesktopNodeTypes.IMAGE_INPUT, node.type)
        assertEquals("", node.params["path"])
        assertEquals(DesktopNodeTypes.DEFAULT_IMG2IMG_STRENGTH, node.params["strength"])

        assertTrue(state.selectNode(node.id))
        val fields = state.snapshot().fields
        assertEquals(listOf("path", "strength"), fields.map { it.key })
        assertEquals("text", fields.single { it.key == "path" }.kind)
        assertEquals("强度", fields.single { it.key == "strength" }.label)

        assertTrue(state.setSelectedParameter("path", "/tmp/init.png"))
        assertTrue(state.setSelectedParameter("strength", "0.4"))
        assertEquals("/tmp/init.png", session.workflow.byId[node.id]!!.params["path"])
        assertEquals("0.4", state.snapshot().fields.single { it.key == "strength" }.value)
        assertTrue(
            "the canvas card should show the edited strength",
            state.snapshot().nodes.single().summary.contains("强度 0.4"),
        )
    }

    @Test
    fun `dragging from an output port to a compatible input wires the shared workflow and reports success`() {
        val session = WorkflowSession(Workflow())
        val state = AdvancedModeState(session)
        state.addNode(DesktopNodeTypes.PROMPT)
        state.addNode(DesktopNodeTypes.SD_SAMPLER)
        state.addNode(DesktopNodeTypes.OUTPUT)

        val feedback = state.dragWire("prompt", "prompt", "sample", "prompt")

        assertTrue(feedback.message.orEmpty(), feedback.connected)
        assertTrue(feedback.message.orEmpty().contains("已连接"))
        assertEquals(Source("prompt", "prompt"), session.workflow.byId["sample"]!!.inputs["prompt"])
        assertEquals(1, state.snapshot().wires.size)
    }

    @Test
    fun `dragging between incompatible ports refuses the wire with a reason and keeps the graph`() {
        val session = WorkflowSession(Workflow())
        val state = AdvancedModeState(session)
        state.addNode(DesktopNodeTypes.PROMPT)
        state.addNode(DesktopNodeTypes.OUTPUT)
        val before = session.workflow.toJson()

        val feedback = state.dragWire("prompt", "prompt", "output", "image")

        assertFalse(feedback.connected)
        assertTrue("expected a readable refusal: ${feedback.message}", feedback.message.orEmpty().contains("PROMPT"))
        assertEquals(before, session.workflow.toJson())
        assertEquals(feedback.message, state.snapshot().message)
    }

    @Test
    fun `editing in advanced and default modes changes one shared workflow and identical parameters`() {
        val session = WorkflowSession()
        val defaultState = DefaultModeState(session)
        val advancedState = AdvancedModeState(session)
        assertSame(session, defaultState.sharedSession)
        assertSame(session, advancedState.sharedSession)

        assertTrue(advancedState.selectNode("sampler"))
        assertTrue(advancedState.setSelectedParameter("steps", "9"))
        assertTrue(defaultState.selectNode("sampler"))
        assertEquals("9", defaultState.snapshot().fields.single { it.key == "steps" }.value)

        assertTrue(defaultState.setSelectedParameter("seed", "77"))
        assertEquals("77", advancedState.snapshot().fields.single { it.key == "seed" }.value)
        assertEquals(3, session.workflow.nodes.size)
    }

    @Test
    fun `advanced mode refuses an oversized steps edit and keeps the workflow unchanged`() {
        val session = WorkflowSession()
        val advancedState = AdvancedModeState(session)
        assertTrue(advancedState.selectNode("sampler"))

        val before = session.workflow.toJson()
        assertFalse(advancedState.setSelectedParameter("steps", "50"))
        assertEquals(before, session.workflow.toJson())
        assertEquals("20", advancedState.snapshot().fields.single { it.key == "steps" }.value)

        assertFalse(advancedState.setSelectedParameter("steps", "abc"))
        assertEquals(before, session.workflow.toJson())
        assertTrue(advancedState.setSelectedParameter("steps", "20"))
        assertEquals("20", session.workflow.byId["sampler"]!!.params["steps"])
    }

    @Test
    fun `a node added in advanced mode appears in the default chain rail`() {
        val session = WorkflowSession(Workflow())
        val defaultState = DefaultModeState(session)
        val advancedState = AdvancedModeState(session)

        assertTrue(advancedState.addNode(DesktopNodeTypes.PROMPT))

        assertEquals(listOf("core.prompt"), defaultState.snapshot().chips.map { it.nodeType })
    }

    @Test
    fun `loading a valid workflow replaces the shared one seen by default and advanced modes`() {
        val session = WorkflowSession()
        val defaultState = DefaultModeState(session)
        val advancedState = AdvancedModeState(session)
        var changes = 0
        val unsubscribe = session.addChangeListener { changes++ }
        val file = Files.createTempFile("workflow-load", ".json")
        val source = Workflow(listOf(DesktopNodeTypes.output.createNode("onlyOutput", Position(10f, 20f))))
        Files.writeString(file, source.toJson())

        val feedback = advancedState.loadWorkflow(file.toString())

        assertTrue(feedback.message, feedback.ok)
        assertTrue("loading must notify every mode that shares the session", changes > 0)
        assertEquals(listOf("onlyOutput"), session.workflow.nodes.map { it.id })
        assertEquals(listOf("onlyOutput"), defaultState.snapshot().chips.map { it.nodeId })
        assertEquals(listOf("onlyOutput"), advancedState.snapshot().nodes.map { it.id })
        unsubscribe()
    }

    @Test
    fun `a broken or missing workflow file only reports an error and keeps every mode's workflow`() {
        val session = WorkflowSession()
        val defaultState = DefaultModeState(session)
        val advancedState = AdvancedModeState(session)
        val before = session.workflow.toJson()
        val bad = Files.createTempFile("workflow-bad", ".json")
        Files.writeString(bad, "{not json")

        val badFeedback = defaultState.loadWorkflow(bad.toString())
        assertFalse(badFeedback.ok)
        assertTrue(badFeedback.message, badFeedback.message.contains("无法解析"))
        assertEquals(before, session.workflow.toJson())
        assertEquals(listOf("prompt", "sampler", "output"), advancedState.snapshot().nodes.map { it.id })

        val missing = advancedState.loadWorkflow("/no/such/dir/missing-workflow.json")
        assertFalse(missing.ok)
        assertTrue(missing.message, missing.message.contains("无法读取"))
        assertEquals(before, session.workflow.toJson())
        assertEquals(listOf("prompt", "sampler", "output"), defaultState.snapshot().chips.map { it.nodeId })
    }

    @Test
    fun `a workflow saved in advanced mode loads back through default mode`() {
        val session = WorkflowSession()
        val advancedState = AdvancedModeState(session)
        assertTrue(advancedState.addNode(DesktopNodeTypes.IMAGE_INPUT))
        val path = Files.createTempFile("workflow-roundtrip", ".json")

        val saved = advancedState.saveWorkflow(path.toString())
        assertTrue(saved.message, saved.ok)

        val otherSession = WorkflowSession()
        val defaultState = DefaultModeState(otherSession)
        val loaded = defaultState.loadWorkflow(path.toString())
        assertTrue(loaded.message, loaded.ok)
        assertEquals(session.workflow.toJson(), otherSession.workflow.toJson())
    }

    @Test
    fun `zoom and pan reuse the copied viewport state`() {
        val state = AdvancedModeState()
        val before = state.snapshot().viewport

        state.zoomAt(Pt(120f, 80f), 1.4f)
        val zoomed = state.snapshot().viewport
        assertTrue(zoomed.scale > before.scale)
        assertEquals(120f, before.toWorld(zoomed.toScreen(Pt(120f, 80f))).x, 0.01f)

        state.panBy(Pt(24f, -16f))
        val panned = state.snapshot().viewport
        assertEquals(zoomed.offset.x + 24f, panned.offset.x, 0.01f)
        assertEquals(zoomed.offset.y - 16f, panned.offset.y, 0.01f)
    }

    @Test
    fun `advanced run uses the shared runner and exposes the same progress and results`() {
        assumePosix()
        val fake = fakeCli()
        val runner = WorkflowRunner(fake.config)
        val state = AdvancedModeState(WorkflowSession(), runner)
        var changes = 0
        val unsubscribe = state.addChangeListener { changes++ }

        assertTrue(state.selectNode("sampler"))
        assertTrue(state.setSelectedParameter("seed", "11"))
        assertTrue(state.triggerRun())

        val run = state.runSnapshot()
        assertEquals(RunStatus.SUCCESS, run.status)
        assertEquals(1, run.progress.size)
        assertEquals("advanced.png", File(run.progress.single().path).name)
        assertEquals(fake.output.absolutePath, run.results.single().path)
        assertEquals("11", run.results.single().params["seed"])
        assertTrue("listeners should see the shared run state", changes > 0)
        unsubscribe()
    }

    private fun assumePosix() {
        Assume.assumeFalse(
            "the fake CLI is a POSIX shell script",
            System.getProperty("os.name").startsWith("Windows"),
        )
    }

    private data class FakeCli(val config: RunConfig, val dir: File, val output: File)

    private fun fakeCli(): FakeCli {
        val dir = Files.createTempDirectory("gui-advanced-test").toFile()
        val output = File(dir, "advanced.png")
        val script = File(dir, "fake-cli.sh")
        script.writeText(
            buildString {
                append("#!/bin/sh\n")
                append("echo 'engine: loading model'\n")
                append("echo '[1/1] seed=11 -> ${output.path}'\n")
                append(": > '${output.path}'\n")
                append("echo 'engine: sampling finished'\n")
                append("exit 0\n")
            },
        )
        script.setExecutable(true)
        return FakeCli(
            RunConfig(
                cliExecutable = script.path,
                modelPath = "/models/sd15.safetensors",
                outputPath = output.path,
                batch = 1,
                timeoutMillis = 10_000L,
            ),
            dir,
            output,
        )
    }
}
