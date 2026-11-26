package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.desktop.graph.DesktopNodeTypes
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider-facing contract of [DesktopToolCommands]: a real model can only
 * call these tools correctly if the schema and the argument tolerance are
 * observable at this seam. Assertions read JSON by field name — JVM org.json
 * does not promise key order (CODEBASE §6).
 */
class DesktopToolCommandsTest {

    @Test
    fun `every tool spec carries an OpenAI-compatible description and parameters`() {
        val specs = DesktopToolCommands.specs()
        assertEquals(5, specs.length())
        val functions = (0 until specs.length()).map { specs.getJSONObject(it) }
        assertTrue(functions.all { it.getString("type") == "function" })
        val byName = functions.associateBy { it.getJSONObject("function").getString("name") }
        assertEquals(
            setOf("new_workflow", "add_node", "set_parameter", "connect", "run"),
            byName.keys,
        )

        byName.forEach { (name, spec) ->
            val fn = spec.getJSONObject("function")
            assertTrue("$name has a description", fn.getString("description").isNotBlank())
            val params = fn.getJSONObject("parameters")
            assertEquals("$name parameters are an object", "object", params.getString("type"))
            assertTrue("$name declares properties", params.has("properties"))
            assertTrue("$name declares required fields", params.has("required"))
        }

        val addNode = byName.getValue("add_node").getJSONObject("function")
        assertTrue(
            "add_node describes the id rules",
            addNode.getString("description").contains("_"),
        )
        val addParams = addNode.getJSONObject("parameters")
        assertEquals(
            setOf("id", "type"),
            addParams.getJSONArray("required").strings().toSet(),
        )
        val addProps = addParams.getJSONObject("properties")
        assertEquals("string", addProps.getJSONObject("type").getString("type"))
        assertEquals(
            listOf("core.prompt", "sd.sample", "core.output"),
            addProps.getJSONObject("type").getJSONArray("enum").strings(),
        )
        assertTrue(
            "the id property warns about duplicates",
            addProps.getJSONObject("id").getString("description").contains("不可重复"),
        )

        val setProps = byName.getValue("set_parameter")
            .getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        assertEquals("string", setProps.getJSONObject("value").getString("type"))
        assertTrue(
            "value is documented as a string",
            setProps.getJSONObject("value").getString("description").contains("字符串"),
        )

        val connectDescription = byName.getValue("connect").getJSONObject("function").getString("description")
        assertTrue("connect names the prompt port", connectDescription.contains("prompt"))
        assertTrue("connect names the image port", connectDescription.contains("image"))
    }

    @Test
    fun `add_node accepts type aliases and both node_type spellings`() {
        val aliases = listOf(
            "prompt" to DesktopNodeTypes.PROMPT,
            "sampler" to DesktopNodeTypes.SD_SAMPLER,
            "sd_sampler" to DesktopNodeTypes.SD_SAMPLER,
            "sample" to DesktopNodeTypes.SD_SAMPLER,
            "output" to DesktopNodeTypes.OUTPUT,
            DesktopNodeTypes.PROMPT to DesktopNodeTypes.PROMPT,
        )
        aliases.forEach { (given, expected) ->
            val command = DesktopToolCommands.fromName(
                "add_node",
                JSONObject().put("id", "n_1").put("node_type", given),
            )
            assertTrue("$given should map to $expected", command is ToolCommand.AddNode && command.type == expected)
        }
        val camel = DesktopToolCommands.fromName(
            "add_node",
            JSONObject().put("id", "n_2").put("nodeType", "sampler"),
        )
        assertEquals(DesktopNodeTypes.SD_SAMPLER, (camel as ToolCommand.AddNode).type)
    }

    @Test
    fun `add_node normalizes the id and stringifies scalar params`() {
        val command = DesktopToolCommands.fromName(
            "add_node",
            JSONObject()
                .put("id", " prompt/1 bad.id ")
                .put("type", "prompt")
                .put(
                    "params",
                    JSONObject()
                        .put("steps", 20)
                        .put("cfgScale", 7.0)
                        .put("save", true)
                        .put("prompt", "a fox"),
                ),
        ) as ToolCommand.AddNode
        assertEquals("prompt_1_bad_id", command.id)
        assertEquals(
            mapOf("steps" to "20", "cfgScale" to "7.0", "save" to "true", "prompt" to "a fox"),
            command.params,
        )
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}
