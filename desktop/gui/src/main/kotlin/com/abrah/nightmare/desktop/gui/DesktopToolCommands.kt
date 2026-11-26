package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.desktop.graph.DesktopNodeTypes
import org.json.JSONArray
import org.json.JSONObject

/**
 * The name↔command seam shared by the production [DesktopToolBox] and the
 * test-source `ToolInterfaceToolBox`: one agent tool name per [ToolCommand]
 * variant. Neither caller's ToolResult conversion lives here — production
 * still attaches run pictures, the fake stays independent (ADR-0008).
 */
object DesktopToolCommands {
    val TOOL_NAMES = listOf("new_workflow", "add_node", "set_parameter", "connect", "run")

    /**
     * A fresh array per call, so each ToolBox keeps its own specs instance.
     * Real providers need the OpenAI-compatible `description`/`parameters`
     * shape: with names alone the model cannot know the argument names, the
     * legal node types or the wire ports, and `add_node` calls get rejected.
     */
    fun specs(): JSONArray = TOOL_NAMES.fold(JSONArray()) { acc, name ->
        acc.put(
            when (name) {
                "new_workflow" -> function(
                    name,
                    "新建唯一的空工作流，清空当前节点与连线；修改工作流时应先调用它。",
                    JSONObject(),
                    emptyList(),
                )

                "add_node" -> function(
                    name,
                    "添加一个节点。id 必须唯一，只能使用字母、数字、下划线 _ 或连字符 -；" +
                        "type 只能是 core.prompt、sd.sample、core.output。" +
                        "也接受 prompt / sampler / sample / sd_sampler / output 这些常见别名。",
                    JSONObject()
                        .put(
                            "id",
                            JSONObject().put("type", "string")
                                .put("description", "节点 id 不可重复；只能包含字母、数字、_ 或 -。"),
                        )
                        .put(
                            "type",
                            JSONObject().put("type", "string")
                                .put("enum", JSONArray(listOf(DesktopNodeTypes.PROMPT, DesktopNodeTypes.SD_SAMPLER, DesktopNodeTypes.OUTPUT)))
                                .put("description", "节点类型：core.prompt（提示词）、sd.sample（采样器）、core.output（输出）。"),
                        )
                        .put("x", JSONObject().put("type", "number").put("description", "画布横坐标（世界坐标），可省略。"))
                        .put("y", JSONObject().put("type", "number").put("description", "画布纵坐标（世界坐标），可省略。"))
                        .put(
                            "params",
                            JSONObject().put("type", "object")
                                .put("additionalProperties", JSONObject().put("type", "string"))
                                .put("description", "节点初始参数对象；所有值必须是字符串，例如 {\"steps\":\"20\"}。"),
                        ),
                    listOf("id", "type"),
                )

                "set_parameter" -> function(
                    name,
                    "给已有节点设置一个参数，例如 prompt 节点的 prompt 或 sampler 节点的 seed；value 一律传字符串。" +
                        "调度器 scheduler 必须传 CLI 值（discrete、karras 等），不要传 normal；" +
                        "采样器 sampler 传引擎名（如 euler、dpm++2m），不要传显示名。",
                    JSONObject()
                        .put("nodeId", JSONObject().put("type", "string").put("description", "已有节点的 id。"))
                        .put("key", JSONObject().put("type", "string").put("description", "参数名，例如 prompt、negative、seed、steps。"))
                        .put("value", JSONObject().put("type", "string").put("description", "参数值；一律传字符串，数字也写成字符串。")),
                    listOf("nodeId", "key", "value"),
                )

                "connect" -> function(
                    name,
                    "连接两个节点。典型连线：prompt 节点的 prompt 输出 -> sampler 节点的 prompt 输入；" +
                        "sampler 节点的 image 输出 -> output 节点的 image 输入。" +
                        "toPort 填目标输入端口名（sampler 用 prompt，output 用 image）；fromPort 可省略。",
                    JSONObject()
                        .put("fromNode", JSONObject().put("type", "string").put("description", "源节点 id。"))
                        .put("toNode", JSONObject().put("type", "string").put("description", "目标节点 id。"))
                        .put("toPort", JSONObject().put("type", "string").put("description", "目标输入端口名：sampler 用 prompt，output 用 image。"))
                        .put("fromPort", JSONObject().put("type", "string").put("description", "源输出端口名：prompt 节点为 prompt，sampler 节点为 image；只有一个输出时可省略。")),
                    listOf("fromNode", "toNode", "toPort"),
                )

                else -> function(name, "运行当前唯一工作流，等待 CLI 完成后把结果图片作为对话气泡返回。", JSONObject(), emptyList())
            },
        )
    }

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>,
    ): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", JSONArray(required)),
                ),
        )

    /** Null means the model called a tool the desktop command surface does not know. */
    fun fromName(name: String, args: JSONObject): ToolCommand? = when (name) {
        "new_workflow" -> ToolCommand.NewWorkflow
        "add_node" -> {
            val rawType = args.optString("type")
                .ifBlank { args.optString("node_type") }
                .ifBlank { args.optString("nodeType") }
            val normalizedType = TYPE_ALIASES[rawType.trim().lowercase()] ?: rawType
            ToolCommand.AddNode(
                id = normalizeNodeId(args.optString("id")),
                type = normalizedType,
                x = args.optDouble("x", 0.0).toFloat(),
                y = args.optDouble("y", 0.0).toFloat(),
                params = args.optJSONObject("params")
                    ?.let { p -> p.keys().asSequence().associateWith { k -> p.get(k).toString() } }
                    ?: emptyMap(),
            )
        }

        "set_parameter" -> ToolCommand.SetParameter(
            args.getString("nodeId"),
            args.getString("key"),
            args.getString("value"),
        )

        "connect" -> ToolCommand.Connect(
            args.getString("fromNode"),
            args.getString("toNode"),
            args.getString("toPort"),
            args.optString("fromPort").takeIf { it.isNotBlank() },
        )

        "run" -> ToolCommand.Run
        else -> null
    }

    /**
     * Provider arguments are free-form JSON: models routinely send a short
     * node type, camelCase `nodeType`, spaces in the id or numeric params.
     * Normalizing here keeps ToolInterface's three rejections untouched while
     * making a well-meant call actually reach them with usable values.
     */
    private val TYPE_ALIASES = mapOf(
        "prompt" to DesktopNodeTypes.PROMPT,
        DesktopNodeTypes.PROMPT to DesktopNodeTypes.PROMPT,
        "sampler" to DesktopNodeTypes.SD_SAMPLER,
        "sd_sampler" to DesktopNodeTypes.SD_SAMPLER,
        "sample" to DesktopNodeTypes.SD_SAMPLER,
        DesktopNodeTypes.SD_SAMPLER to DesktopNodeTypes.SD_SAMPLER,
        "output" to DesktopNodeTypes.OUTPUT,
        DesktopNodeTypes.OUTPUT to DesktopNodeTypes.OUTPUT,
    )

    /** Wires and file names only accept ASCII letters/digits/`_`/`-` (ADR-0007). */
    private fun normalizeNodeId(raw: String): String = raw.trim().map { c ->
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-') c else '_'
    }.joinToString("")
}
