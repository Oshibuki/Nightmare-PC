package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.desktop.graph.DesktopNodeTypes
import com.abrah.nightmare.desktop.graph.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Observable default-mode state: chain chips, selected drawer and the shared workflow. */
class DefaultModeStateTest {

    @Test
    fun `opening the app loads a built-in txt2img chain with the prompt drawer selected`() {
        val snapshot = DefaultModeState().snapshot()

        assertEquals(listOf("prompt", "sampler", "output"), snapshot.chips.map { it.nodeId })
        assertEquals(
            listOf(DesktopNodeTypes.PROMPT, DesktopNodeTypes.SD_SAMPLER, DesktopNodeTypes.OUTPUT),
            snapshot.chips.map { it.nodeType },
        )
        assertEquals(listOf("提示词", "SD 采样器", "输出"), snapshot.chips.map { it.title })
        assertEquals("prompt", snapshot.selectedNodeId)
        assertEquals(listOf("prompt", "negative"), snapshot.fields.map { it.key })
        assertTrue(snapshot.preview.title.contains("合成"))
    }

    @Test
    fun `selecting the sampler chip opens sampler, scheduler, steps, CFG, seed and size controls`() {
        val state = DefaultModeState()

        assertTrue(state.selectNode("sampler"))
        val fields = state.snapshot().fields
        assertEquals(
            listOf("sampler", "scheduler", "steps", "cfgScale", "seed", "width", "height"),
            fields.map { it.key },
        )
        assertEquals("euler", fields.single { it.key == "sampler" }.value)
        assertEquals("discrete", fields.single { it.key == "scheduler" }.value)
        assertEquals("20", fields.single { it.key == "steps" }.value)
        assertTrue(fields.single { it.key == "sampler" }.options.contains("dpmpp_2m"))
        assertTrue(fields.single { it.key == "scheduler" }.options.contains("karras"))
    }

    @Test
    fun `editing a parameter changes the selected drawer and the shared workflow immediately`() {
        val session = WorkflowSession()
        val state = DefaultModeState(session)

        assertTrue(state.selectNode("sampler"))
        assertTrue(state.setSelectedParameter("steps", "12"))
        assertTrue(state.setSelectedParameter("seed", "42"))

        val fields = state.snapshot().fields
        assertEquals("12", fields.single { it.key == "steps" }.value)
        assertEquals("42", fields.single { it.key == "seed" }.value)
        assertEquals("12", session.workflow.byId["sampler"]!!.params["steps"])
        assertEquals("42", session.workflow.byId["sampler"]!!.params["seed"])
    }

    @Test
    fun `steps edits above the cap and non numeric steps are rejected without changing the workflow`() {
        val session = WorkflowSession()
        val state = DefaultModeState(session)
        assertTrue(state.selectNode("sampler"))
        val before = session.workflow

        assertFalse("the drawer must reject 50 steps", state.setSelectedParameter("steps", "50"))
        assertEquals(before, session.workflow)
        assertFalse("the shared session must reject 50 steps too", session.setNodeParameter("sampler", "steps", "50"))
        assertEquals(before, session.workflow)
        assertFalse(state.setSelectedParameter("steps", "many"))
        assertEquals(before, session.workflow)

        assertTrue(state.setSelectedParameter("steps", "20"))
        assertEquals("20", session.workflow.byId["sampler"]!!.params["steps"])
        assertTrue(state.setSelectedParameter("steps", "12"))
        assertEquals("12", session.workflow.byId["sampler"]!!.params["steps"])
    }

    @Test
    fun `prompt and output chips edit their own parameters and update chip summaries`() {
        val state = DefaultModeState()

        assertTrue(state.setSelectedParameter("prompt", "moonlit harbor"))
        assertEquals("moonlit harbor", state.snapshot().chips.single { it.nodeId == "prompt" }.summary)

        assertTrue(state.setSelectedParameter("negative", "blurry"))
        assertTrue(state.snapshot().chips.single { it.nodeId == "prompt" }.summary.contains("moonlit harbor"))
        assertTrue(state.snapshot().chips.single { it.nodeId == "prompt" }.summary.contains("blurry"))

        assertTrue(state.selectNode("output"))
        assertEquals(listOf("save"), state.snapshot().fields.map { it.key })
        assertTrue(state.setSelectedParameter("save", "false"))
        assertEquals("save=false", state.snapshot().chips.single { it.nodeId == "output" }.summary)
    }

    @Test
    fun `an external tool editing the same workflow is visible in the default mode`() {
        val session = WorkflowSession()
        val state = DefaultModeState(session)
        val sampler = session.workflow.byId["sampler"]!!
        val edited = session.workflow.copy(
            nodes = session.workflow.nodes.map { node ->
                if (node.id == "sampler") {
                    node.copy(params = node.params + ("width" to "768"))
                } else {
                    node
                }
            },
        )

        session.replaceWorkflow(edited)
        assertEquals("768", session.workflow.byId["sampler"]!!.params["width"])

        assertTrue(state.selectNode("sampler"))
        assertEquals("768", state.snapshot().fields.single { it.key == "width" }.value)
        assertTrue(state.snapshot().preview.detail.contains("768"))
        assertEquals(sampler.id, state.snapshot().chips[1].nodeId)
    }

    @Test
    fun `unknown chips and unknown parameters are rejected without changing the workflow`() {
        val state = DefaultModeState()
        val before = state.snapshot()

        assertFalse(state.selectNode("missing"))
        assertFalse(state.setSelectedParameter("missing", "value"))

        val after = state.snapshot()
        assertEquals(before, after)
    }

    @Test
    fun `built-in chain wires prompt to sampler to output without any engine or weights`() {
        val workflow = builtInTxt2imgWorkflow()

        assertEquals(
            listOf("prompt", "sampler", "output"),
            workflow.nodes.map { node: Node -> node.id },
        )
        assertEquals(2, workflow.connections.size)
        assertEquals("prompt", workflow.connections[0].from.node)
        assertEquals("sampler", workflow.connections[0].toNode)
        assertEquals("sampler", workflow.connections[1].from.node)
        assertEquals("output", workflow.connections[1].toNode)
    }
}
