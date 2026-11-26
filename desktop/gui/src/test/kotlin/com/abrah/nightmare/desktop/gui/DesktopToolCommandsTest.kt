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
        assertEquals(12, specs.length())
        val functions = (0 until specs.length()).map { specs.getJSONObject(it) }
        assertTrue(functions.all { it.getString("type") == "function" })
        val byName = functions.associateBy { it.getJSONObject("function").getString("name") }
        assertEquals(
            setOf(
                "new_workflow", "add_node", "set_parameter", "connect", "run",
                "list_models", "get_canvas", "new_flow", "save_flow", "list_loras",
                "download_lora", "delete_lora",
            ),
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
            listOf(
                DesktopNodeTypes.PROMPT,
                DesktopNodeTypes.IMAGE_INPUT,
                DesktopNodeTypes.SD_SAMPLER,
                DesktopNodeTypes.OUTPUT,
            ),
            addProps.getJSONObject("type").getJSONArray("enum").strings(),
        )
        assertTrue(
            "the id property warns about duplicates",
            addProps.getJSONObject("id").getString("description").contains("不可重复"),
        )

        val setDescription = byName.getValue("set_parameter").getJSONObject("function").getString("description")
        assertTrue("scheduler documents a CLI value", setDescription.contains("discrete"))
        assertTrue("scheduler warns against normal", setDescription.contains("normal"))
        assertTrue("sampler documents an engine name", setDescription.contains("euler"))

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
    fun `the five mapped tools describe their arguments to a real provider`() {
        val specs = DesktopToolCommands.specs()
        val byName = (0 until specs.length())
            .map { specs.getJSONObject(it).getJSONObject("function") }
            .associateBy { it.getString("name") }

        val save = byName.getValue("save_flow")
        assertTrue("save_flow must explain the path: ${save.getString("description")}", save.getString("description").contains("路径"))
        assertEquals(
            listOf("path"),
            save.getJSONObject("parameters").getJSONArray("required").strings(),
        )
        assertEquals("string", save.getJSONObject("parameters").getJSONObject("properties").getJSONObject("path").getString("type"))
        assertTrue("save_flow must document the overwrite confirmation", save.getString("description").contains("确认"))

        val listModels = byName.getValue("list_models")
        assertEquals("string", listModels.getJSONObject("parameters").getJSONObject("properties").getJSONObject("dir").getString("type"))
        assertTrue("dir is optional", listModels.getJSONObject("parameters").getJSONArray("required").length() == 0)

        val listLoras = byName.getValue("list_loras")
        assertTrue(
            "list_loras must say it only enumerates: ${listLoras.getString("description")}",
            listLoras.getString("description").contains("不下载"),
        )
        assertTrue("get_canvas returns JSON", toolDescription("get_canvas").contains("JSON"))
        assertTrue("new_flow resets the graph", toolDescription("new_flow").contains("清空"))
    }

    @Test
    fun `the five mapped names resolve to dispatchable commands`() {
        assertEquals(ToolCommand.GetCanvas, DesktopToolCommands.fromName("get_canvas", JSONObject()))
        assertEquals(ToolCommand.NewFlow, DesktopToolCommands.fromName("new_flow", JSONObject()))
        assertEquals(ToolCommand.NewWorkflow, DesktopToolCommands.fromName("new_workflow", JSONObject()))
        assertEquals(
            ToolCommand.SaveFlow("/tmp/desktop-flow.json"),
            DesktopToolCommands.fromName("save_flow", JSONObject().put("path", "/tmp/desktop-flow.json")),
        )
        assertEquals(
            ToolCommand.ListModels("/tmp/models"),
            DesktopToolCommands.fromName("list_models", JSONObject().put("dir", "/tmp/models")),
        )
        assertEquals(
            ToolCommand.ListLoras("/tmp/models"),
            DesktopToolCommands.fromName("list_loras", JSONObject().put("dir", "/tmp/models")),
        )
    }

    @Test
    fun `the two LoRA management tools document their arguments and map to commands`() {
        val download = toolDescription("download_lora")
        assertTrue("download_lora must name the URL: $download", download.contains("URL"))
        assertTrue("download_lora must document the size confirmation: $download", download.contains("确认"))
        val delete = toolDescription("delete_lora")
        assertTrue("delete_lora must document the confirmation: $delete", delete.contains("确认"))
        assertTrue("delete_lora must name the LoRA directory: $delete", delete.contains("LoRA"))

        val specs = DesktopToolCommands.specs()
        val byName = (0 until specs.length())
            .map { specs.getJSONObject(it).getJSONObject("function") }
            .associateBy { it.getString("name") }
        val downloadParams = byName.getValue("download_lora").getJSONObject("parameters")
        assertEquals(listOf("url"), downloadParams.getJSONArray("required").strings())
        assertEquals("string", downloadParams.getJSONObject("properties").getJSONObject("url").getString("type"))
        assertTrue("only url is required; dir stays optional", downloadParams.getJSONArray("required").length() == 1)
        val deleteParams = byName.getValue("delete_lora").getJSONObject("parameters")
        assertEquals(listOf("path"), deleteParams.getJSONArray("required").strings())
        assertEquals("string", deleteParams.getJSONObject("properties").getJSONObject("path").getString("type"))

        assertEquals(
            ToolCommand.DownloadLora("https://example.invalid/a.safetensors", "/tmp/models"),
            DesktopToolCommands.fromName(
                "download_lora",
                JSONObject().put("url", "https://example.invalid/a.safetensors").put("dir", "/tmp/models"),
            ),
        )
        assertEquals(
            ToolCommand.DeleteLora("/tmp/models/loras/a.safetensors"),
            DesktopToolCommands.fromName(
                "delete_lora",
                JSONObject().put("path", "/tmp/models/loras/a.safetensors"),
            ),
        )
    }

    @Test
    fun `set_parameter documents the steps cap for a real provider`() {
        val description = toolDescription("set_parameter")

        assertTrue("steps must be named: $description", description.contains("steps"))
        assertTrue("the 20-step cap must be named: $description", description.contains("20"))
        assertTrue(
            "oversized steps must be called out: $description",
            description.contains("拒绝") || description.contains("钳制"),
        )
    }

    @Test
    fun `add_node accepts type aliases and both node_type spellings`() {
        val aliases = listOf(
            "prompt" to DesktopNodeTypes.PROMPT,
            "image" to DesktopNodeTypes.IMAGE_INPUT,
            "img" to DesktopNodeTypes.IMAGE_INPUT,
            "init_image" to DesktopNodeTypes.IMAGE_INPUT,
            "initImage" to DesktopNodeTypes.IMAGE_INPUT,
            "sampler" to DesktopNodeTypes.SD_SAMPLER,
            "sd_sampler" to DesktopNodeTypes.SD_SAMPLER,
            "sample" to DesktopNodeTypes.SD_SAMPLER,
            "output" to DesktopNodeTypes.OUTPUT,
            DesktopNodeTypes.PROMPT to DesktopNodeTypes.PROMPT,
            DesktopNodeTypes.IMAGE_INPUT to DesktopNodeTypes.IMAGE_INPUT,
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

    private fun toolDescription(name: String): String {
        val specs = DesktopToolCommands.specs()
        return (0 until specs.length())
            .map { specs.getJSONObject(it).getJSONObject("function") }
            .first { it.getString("name") == name }
            .getString("description")
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}
