package com.abrah.nightmare.desktop.gui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

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
        assertEquals(12, mappedBox.specs.length())

        val canvas = mappedBox.call("get_canvas", JSONObject())
        assertTrue(canvas.ok)
        assertTrue(canvas.json.getString("ok").contains("sampler"))
        assertTrue(canvas.pictures.isEmpty())

        val cleared = mappedBox.call("new_flow", JSONObject())
        assertTrue(cleared.ok)
        assertTrue(session.workflow.nodes.isEmpty())

        val unknown = mappedBox.call("no_such_tool", JSONObject())
        assertTrue(!unknown.ok)
        assertTrue(unknown.json.getString("error").contains("unknown tool"))
    }

    @Test
    fun `the ask injected into the tool box reaches an overwrite and a cancel keeps the old file`() {
        val target = Files.createTempDirectory("toolbox-ask").resolve("flow.json")
        Files.writeString(target, "old")
        val seen = mutableListOf<Pair<String, List<String>>>()
        val box = DesktopToolBox(ToolInterface(WorkflowSession(), runControl), runControl) { q, opts ->
            seen += q to opts
            "取消"
        }

        val result = box.call("save_flow", JSONObject().put("path", target.toString()))
        assertTrue(result.ok)
        assertEquals("old", Files.readString(target))
        assertEquals(1, seen.size)
        assertEquals("覆盖已保存的工作流?", seen.single().first)
        assertEquals(listOf("保存", "取消"), seen.single().second)
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
