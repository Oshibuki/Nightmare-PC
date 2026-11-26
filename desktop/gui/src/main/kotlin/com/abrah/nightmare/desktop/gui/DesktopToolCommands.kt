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
    val TOOL_NAMES = listOf(
        "new_workflow", "add_node", "set_parameter", "connect", "run",
        "list_models", "get_canvas", "new_flow", "save_flow", "list_loras",
        "download_lora", "delete_lora",
    )

    /** Optional library-root argument, shared by the two read-only inventory tools. */
    private fun dirProperties(): JSONObject = JSONObject().put(
        "dir",
        JSONObject()
            .put("type", "string")
            .put(
                "description",
                "可选的模型根目录；省略时使用 NIGHTMARE_MODELS 环境变量，再回退到 ~/.cache/nightmare-desktop/models。",
            ),
    )

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
                        "type 只能是 core.prompt、core.image、sd.sample、core.output。" +
                        "也接受 prompt / image / img / init_image / sampler / sample / sd_sampler / output 这些常见别名。" +
                        "图生图用 core.image 节点：params 里写本地 PNG 路径 path 与重绘强度 strength（字符串 0..1，默认 \"0.75\"）。",
                    JSONObject()
                        .put(
                            "id",
                            JSONObject().put("type", "string")
                                .put("description", "节点 id 不可重复；只能包含字母、数字、_ 或 -。"),
                        )
                        .put(
                            "type",
                            JSONObject().put("type", "string")
                                .put(
                                    "enum",
                                    JSONArray(
                                        listOf(
                                            DesktopNodeTypes.PROMPT,
                                            DesktopNodeTypes.IMAGE_INPUT,
                                            DesktopNodeTypes.SD_SAMPLER,
                                            DesktopNodeTypes.OUTPUT,
                                        ),
                                    ),
                                )
                                .put(
                                    "description",
                                    "节点类型：core.prompt（提示词）、core.image（图片输入）、sd.sample（采样器）、core.output（输出）。",
                                ),
                        )
                        .put("x", JSONObject().put("type", "number").put("description", "画布横坐标（世界坐标），可省略。"))
                        .put("y", JSONObject().put("type", "number").put("description", "画布纵坐标（世界坐标），可省略。"))
                        .put(
                            "params",
                            JSONObject().put("type", "object")
                                .put("additionalProperties", JSONObject().put("type", "string"))
                                .put(
                                    "description",
                                    "节点初始参数对象；所有值必须是字符串，例如 {\"steps\":\"20\"}。" +
                                        "core.image 用 path（本地 PNG 路径）与 strength（字符串 0..1，默认 \"0.75\"）。",
                                ),
                        ),
                    listOf("id", "type"),
                )

                "set_parameter" -> function(
                    name,
                    "给已有节点设置一个参数，例如 prompt 节点的 prompt、sampler 节点的 seed 或 core.image 节点的 path/strength；value 一律传字符串。" +
                        "core.image 的 path 是本地 PNG 路径，strength 是 0..1 的字符串，默认 \"0.75\"。" +
                        "调度器 scheduler 必须传 CLI 值（discrete、karras 等），不要传 normal；" +
                        "采样器 sampler 传引擎名（如 euler、dpm++2m），不要传显示名；" +
                        "steps 最大 $MAX_RUN_STEPS，超过会被拒绝；旧工作流里更大的步数在运行时钳制为 $MAX_RUN_STEPS。",
                    JSONObject()
                        .put("nodeId", JSONObject().put("type", "string").put("description", "已有节点的 id。"))
                        .put("key", JSONObject().put("type", "string").put("description", "参数名，例如 prompt、negative、seed、steps、path、strength。"))
                        .put("value", JSONObject().put("type", "string").put("description", "参数值；一律传字符串，数字也写成字符串。")),
                    listOf("nodeId", "key", "value"),
                )

                "connect" -> function(
                    name,
                    "连接两个节点。典型连线：prompt:prompt -> sampler:prompt；sampler:image -> output:image；" +
                        "图生图另加 core.image:image -> sampler:initImage（prompt 连线保持不变）。" +
                        "toPort 填目标输入端口名（sampler 用 prompt 或 initImage，output 用 image）；fromPort 可省略。",
                    JSONObject()
                        .put("fromNode", JSONObject().put("type", "string").put("description", "源节点 id。"))
                        .put("toNode", JSONObject().put("type", "string").put("description", "目标节点 id。"))
                        .put("toPort", JSONObject().put("type", "string").put("description", "目标输入端口名：sampler 用 prompt 或 initImage，output 用 image。"))
                        .put("fromPort", JSONObject().put("type", "string").put("description", "源输出端口名：core.image 与 sampler 均为 image，prompt 节点为 prompt；只有一个输出时可省略。")),
                    listOf("fromNode", "toNode", "toPort"),
                )

                "list_models" -> function(
                    name,
                    "列出本地模型库中可读取的模型（.safetensors 与 .gguf）路径、格式与张量信息；" +
                        "损坏的文件单独列在 rejected 里。只读，不下载模型。",
                    dirProperties(),
                    emptyList(),
                )

                "get_canvas" -> function(
                    name,
                    "读取当前唯一工作流的 JSON：节点 id、节点类型、参数、画布坐标与连线都在里面；" +
                        "在改完工作流后调用它，可以看到自己改成了什么。",
                    JSONObject(),
                    emptyList(),
                )

                "new_flow" -> function(
                    name,
                    "新建唯一空工作流：清空当前所有节点与连线。与 new_workflow 行为完全一致（移动版工具名）。",
                    JSONObject(),
                    emptyList(),
                )

                "save_flow" -> function(
                    name,
                    "把当前唯一工作流保存到参数 path 指定的本地路径；不载入工作流。" +
                        "目标文件已存在时会先发确认型追问：选「保存」才覆盖，选「取消」则保留原文件且工作流不变。" +
                        "父目录必须存在且可写，失败时返回错误原因。",
                    JSONObject().put(
                        "path",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "目标工作流 JSON 文件路径；父目录必须已存在且可写。"),
                    ),
                    listOf("path"),
                )

                "list_loras" -> function(
                    name,
                    "枚举模型根目录下名字含 lora 的目录里的 .safetensors 文件，返回路径列表；" +
                        "损坏的文件单独列在 rejected 里。只读：不下载、不删除、不搜索。",
                    dirProperties(),
                    emptyList(),
                )

                "download_lora" -> function(
                    name,
                    "从给定 URL 下载一个 LoRA（.safetensors）到模型根目录的 lora 子目录。" +
                        "源端声明的大小达到 512 MB 阈值时会先发确认型追问：选「下载」才写入，选「取消」则不落盘。" +
                        "省略 dir 时使用 NIGHTMARE_MODELS 环境变量或 ~/.cache/nightmare-desktop/models。",
                    JSONObject()
                        .put(
                            "url",
                            JSONObject().put("type", "string")
                                .put("description", "LoRA 文件的下载地址（http/https）；URL 查询参数不会写进文件名或结果。"),
                        )
                        .put("dir", dirProperties().getJSONObject("dir")),
                    listOf("url"),
                )

                "delete_lora" -> function(
                    name,
                    "删除模型根目录 lora 子目录里的一个 LoRA 文件，路径必须在模型根目录内的 lora 目录里。" +
                        "删除前必定发确认型追问：选「删除」才真正删除，选「取消」则文件保留。" +
                        "省略 dir 时使用 NIGHTMARE_MODELS 环境变量或 ~/.cache/nightmare-desktop/models。",
                    JSONObject()
                        .put(
                            "path",
                            JSONObject().put("type", "string")
                                .put("description", "要删除的 LoRA .safetensors 文件路径；越出 lora 目录会被拒绝。"),
                        )
                        .put("dir", dirProperties().getJSONObject("dir")),
                    listOf("path"),
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

        "list_models" -> ToolCommand.ListModels(args.optString("dir").takeIf { it.isNotBlank() })
        "get_canvas" -> ToolCommand.GetCanvas
        "new_flow" -> ToolCommand.NewFlow
        "save_flow" -> ToolCommand.SaveFlow(args.getString("path"))
        "list_loras" -> ToolCommand.ListLoras(args.optString("dir").takeIf { it.isNotBlank() })
        "download_lora" -> ToolCommand.DownloadLora(
            args.getString("url"),
            args.optString("dir").takeIf { it.isNotBlank() },
        )
        "delete_lora" -> ToolCommand.DeleteLora(
            args.getString("path"),
            args.optString("dir").takeIf { it.isNotBlank() },
        )
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
        "image" to DesktopNodeTypes.IMAGE_INPUT,
        "img" to DesktopNodeTypes.IMAGE_INPUT,
        "init_image" to DesktopNodeTypes.IMAGE_INPUT,
        "initimage" to DesktopNodeTypes.IMAGE_INPUT,
        "image_input" to DesktopNodeTypes.IMAGE_INPUT,
        DesktopNodeTypes.IMAGE_INPUT to DesktopNodeTypes.IMAGE_INPUT,
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
