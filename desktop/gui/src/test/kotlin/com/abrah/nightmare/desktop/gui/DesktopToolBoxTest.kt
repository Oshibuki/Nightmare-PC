package com.abrah.nightmare.desktop.gui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat bubble must show what the model actually sent when a tool is rejected. */
class DesktopToolBoxTest {

    private val runControl = object : WorkflowRunControl {
        override fun triggerRun(): Boolean = true
        override fun runSnapshot(): RunSnapshot = RunSnapshot()
    }
    private val box = DesktopToolBox(ToolInterface(WorkflowSession(), runControl), runControl)

    @Test
    fun `mapped read-only tools flow through the production tool box without pictures`() {
        val session = WorkflowSession()
        val mappedBox = DesktopToolBox(ToolInterface(session, runControl), runControl)
        assertEquals(10, mappedBox.specs.length())

        val canvas = mappedBox.call("get_canvas", JSONObject())
        assertTrue(canvas.ok)
        assertTrue(canvas.json.getString("ok").contains("sampler"))
        assertTrue(canvas.pictures.isEmpty())

        val cleared = mappedBox.call("new_flow", JSONObject())
        assertTrue(cleared.ok)
        assertTrue(session.workflow.nodes.isEmpty())

        val unknown = mappedBox.call("delete_lora", JSONObject())
        assertTrue(!unknown.ok)
        assertTrue(unknown.json.getString("error").contains("unknown tool"))
    }

    @Test
    fun `summary shows the tool name and its compact arguments`() {
        val summary = box.summary(
            "add_node",
            JSONObject().put("id", "prompt_1").put("type", "core.prompt"),
        )
        assertTrue(summary, summary.startsWith("add_node "))
        assertTrue(summary, summary.contains("\"id\"") && summary.contains("prompt_1"))
        assertTrue(summary, summary.contains("\"type\"") && summary.contains("core.prompt"))
    }

    @Test
    fun `summary truncates long arguments so a rejected call stays readable`() {
        val summary = box.summary(
            "set_parameter",
            JSONObject().put("nodeId", "prompt").put("key", "prompt").put("value", "x".repeat(400)),
        )
        assertTrue("summary is too long: ${summary.length}", summary.length <= "set_parameter ".length + 121)
        assertTrue(summary, summary.endsWith("…"))
    }
}
