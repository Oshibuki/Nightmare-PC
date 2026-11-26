package com.abrah.nightmare.desktop.graph

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Public behaviour of the desktop-only workflow graph; no Android classes involved. */
class MinimalGraphTest {

    @Test
    fun `built-in node types create the three txt2img nodes from presentation metadata`() {
        assertEquals(
            setOf("core.prompt", "sd.sample", "core.output"),
            DesktopNodeTypes.registry.keys,
        )

        val prompt = DesktopNodeTypes.prompt.createNode("prompt", Position(10f, 20f))
        val sampler = DesktopNodeTypes.sdSampler.createNode("sampler")
        val output = DesktopNodeTypes.output.createNode("output")

        assertEquals("core.prompt", prompt.type)
        assertEquals(Position(10f, 20f), prompt.position)
        assertEquals(setOf("prompt", "negative"), prompt.params.keys)
        assertEquals("euler", sampler.params["sampler"])
        assertEquals("discrete", sampler.params["scheduler"])
        assertEquals("20", sampler.params["steps"])
        assertEquals("core.output", output.type)
        assertEquals(300f, DesktopNodeTypes.output.defaultWidth, 0f)
        assertTrue(DesktopNodeTypes.output.showsResult)
        assertTrue(!DesktopNodeTypes.sdSampler.showsResult)
    }

    @Test
    fun `three txt2img nodes can be chained into a workflow`() {
        val prompt = DesktopNodeTypes.prompt.createNode("prompt")
        val sampler = DesktopNodeTypes.sdSampler.createNode("sampler")
        val output = DesktopNodeTypes.output.createNode("output")
        val workflow = Workflow(listOf(prompt, sampler, output))

        val first = workflow.connect(fromNode = "prompt", toNode = "sampler", toPort = "prompt")
        assertTrue(first is ConnectionResult.Connected)
        val second = (first as ConnectionResult.Connected).workflow.connect("sampler", "output", "image")
        assertTrue(second is ConnectionResult.Connected)

        assertEquals(
            listOf(
                Connection(Source("prompt", "prompt"), "sampler", "prompt"),
                Connection(Source("sampler", "image"), "output", "image"),
            ),
            (second as ConnectionResult.Connected).workflow.connections,
        )
    }

    @Test
    fun `illegal wires are rejected and leave the workflow unchanged`() {
        val workflow = Workflow(
            listOf(
                DesktopNodeTypes.prompt.createNode("prompt"),
                DesktopNodeTypes.sdSampler.createNode("sampler"),
                DesktopNodeTypes.output.createNode("output"),
            ),
        )

        val rejections = listOf(
            workflow.connect("missing", "sampler", "prompt") to "source node not found",
            workflow.connect("prompt", "missing", "prompt") to "target node not found",
            workflow.connect("prompt", "prompt", "prompt") to "itself",
            workflow.connect("output", "sampler", "prompt") to "no output port",
            workflow.connect("prompt", "sampler", "image") to "unknown input port",
            workflow.connect("prompt", "output", "image") to "port type mismatch",
        )

        rejections.forEach { (result, reason) ->
            assertTrue(result is ConnectionResult.Rejected)
            assertTrue(
                "reason was '${(result as ConnectionResult.Rejected).reason}'",
                result.reason.contains(reason),
            )
            assertEquals(workflow, result.workflow)
        }
    }

    @Test
    fun `workflow serializes to the format one wire shape`() {
        val prompt = DesktopNodeTypes.prompt.createNode("prompt")
        val sampler = DesktopNodeTypes.sdSampler.createNode("sampler", Position(100f, 25f))
        val output = DesktopNodeTypes.output.createNode("output")
        val chained = Workflow(listOf(prompt, sampler, output))
            .connect("prompt", "sampler", "prompt") as ConnectionResult.Connected
        val workflow = chained.workflow.connect("sampler", "output", "image") as ConnectionResult.Connected

        val json = JSONObject(workflow.workflow.toJson())
        assertEquals(1, json.getInt("format"))

        val nodes = json.getJSONArray("nodes")
        assertEquals(3, nodes.length())
        val ids = (0 until nodes.length()).map { nodes.getJSONObject(it).getString("id") }
        assertEquals(listOf("prompt", "sampler", "output"), ids)
        assertEquals("core.prompt", nodes.getJSONObject(0).getString("type"))
        assertEquals("", nodes.getJSONObject(0).getJSONObject("params").getString("negative"))

        val wiredSampler = nodes.getJSONObject(1)
        assertEquals("sd.sample", wiredSampler.getString("type"))
        assertEquals("prompt:prompt", wiredSampler.getJSONObject("inputs").getString("prompt"))
        assertEquals(100.0, wiredSampler.getJSONObject("position").getDouble("x"), 0.0)
        assertEquals(25.0, wiredSampler.getJSONObject("position").getDouble("y"), 0.0)
        assertEquals("sampler:image", nodes.getJSONObject(2).getJSONObject("inputs").getString("image"))
    }
}
