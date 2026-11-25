package com.abrah.nightmare.desktop.graph

/**
 * Desktop-only workflow presentation model (ADR-0007). Written from scratch in
 * `desktop/`; it is not a copy of the Android `NodeType`/`Graph` and must never
 * gain execution semantics (no backend handles, cache keys, app-side flags,
 * NPU/video node kinds). The vocabulary follows `GLOSSARY.md`: 工作流,
 * 节点类型, 交互模式.
 */

/** Canvas position in world units. Kept here so :core has no Compose dependency. */
data class Position(val x: Float, val y: Float)

/** A connectable socket. [type] is a wire-compatibility label used for drawing. */
data class Port(val name: String, val type: String)

/** A parameter row the default drawer and the advanced properties panel render. */
data class Widget(val name: String, val label: String, val kind: String, val default: String? = null)

/**
 * Presentation-only 节点类型. Anything that decides HOW a workflow executes
 * belongs to the CLI, not here.
 */
data class NodeType(
    val name: String,
    val title: String,
    val inputs: List<Port> = emptyList(),
    val outputs: List<Port> = emptyList(),
    val defaultWidth: Float = 190f,
    val showsResult: Boolean = false,
    val widgets: List<Widget> = emptyList(),
) {
    /** Ports drawn for this node; a hook for node types whose ports follow wires. */
    fun inputsFor(node: Node): List<Port> = inputs

    /** Creates a node with each widget's default, overridable by [params]. */
    fun createNode(
        id: String,
        position: Position = Position(0f, 0f),
        params: Map<String, String> = emptyMap(),
    ): Node {
        require(isSafeNodeId(id)) {
            "Unsafe node id '$id': use letters, digits, '_' or '-' only"
        }
        val defaults = widgets.mapNotNull { w -> w.default?.let { w.name to it } }.toMap()
        return Node(id = id, type = name, params = defaults + params, position = position)
    }
}

/** Ids travel in file names and in `node:port` wire text, so keep them unambiguous. */
fun isSafeNodeId(id: String): Boolean = id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }

/** One node in a 工作流: its type, widget values, incoming wires and canvas position. */
data class Node(
    val id: String,
    val type: String,
    val params: Map<String, String> = emptyMap(),
    val inputs: Map<String, Source> = emptyMap(),
    val position: Position = Position(0f, 0f),
)

/** The far end of a wire. A null [port] means the source node's sole output. */
data class Source(val node: String, val port: String? = null) {
    override fun toString(): String = if (port == null) node else "$node$SEP$port"

    companion object {
        const val SEP = ':'

        fun parse(text: String): Source {
            val i = text.indexOf(SEP)
            return if (i < 0) Source(text) else Source(text.substring(0, i), text.substring(i + 1))
        }
    }
}

/** The three txt2img node types shipped with the desktop GUI. */
object DesktopNodeTypes {
    const val PROMPT = "core.prompt"
    const val SD_SAMPLER = "sd.sample"
    const val OUTPUT = "core.output"

    val prompt = NodeType(
        name = PROMPT,
        title = "提示词",
        outputs = listOf(Port("prompt", "PROMPT")),
        defaultWidth = 300f,
        widgets = listOf(
            Widget("prompt", "正向提示词", "text", ""),
            Widget("negative", "负向提示词", "text", ""),
        ),
    )

    val sdSampler = NodeType(
        name = SD_SAMPLER,
        title = "SD 采样器",
        inputs = listOf(Port("prompt", "PROMPT")),
        outputs = listOf(Port("image", "IMAGE")),
        widgets = listOf(
            Widget("sampler", "采样器", "choice", "euler"),
            Widget("scheduler", "调度器", "choice", "normal"),
            Widget("steps", "步数", "int", "20"),
            Widget("cfgScale", "CFG", "float", "7.0"),
            Widget("seed", "Seed", "int", "-1"),
            Widget("width", "宽度", "int", "512"),
            Widget("height", "高度", "int", "512"),
        ),
    )

    val output = NodeType(
        name = OUTPUT,
        title = "输出",
        inputs = listOf(Port("image", "IMAGE")),
        defaultWidth = 300f,
        showsResult = true,
        widgets = listOf(Widget("save", "保存图片", "boolean", "true")),
    )

    val registry: Map<String, NodeType> = listOf(prompt, sdSampler, output).associateBy { it.name }
}
