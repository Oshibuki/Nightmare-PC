package com.abrah.nightmare.desktop.graph

import com.abrah.nightmare.desktop.core.SamplerWire

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

/** Desktop-authored workflow node types (ADR-0007); no execution semantics here. */
object DesktopNodeTypes {
    const val PROMPT = "core.prompt"
    const val SD_SAMPLER = "sd.sample"
    const val OUTPUT = "core.output"
    const val IMAGE_INPUT = "core.image"

    /** The one img2img strength default, shared by the node widget and the runner fallback. */
    const val DEFAULT_IMG2IMG_STRENGTH = "0.75"

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
        // The original prompt:PROMPT port is untouched. img2img feeds the
        // separate initImage:IMAGE port, so txt2img wires keep their meaning
        // (ADR-0013). The runner switches subcommand by node presence, not by
        // treating a picture as a prompt.
        inputs = listOf(Port("prompt", "PROMPT"), Port("initImage", "IMAGE")),
        outputs = listOf(Port("image", "IMAGE")),
        widgets = listOf(
            Widget("sampler", "采样器", "choice", "euler"),
            Widget("scheduler", "调度器", "choice", SamplerWire.DEFAULT_SCHEDULER),
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

    /**
     * Ordinary img2img source (ADR-0013): a local PNG path plus its strength.
     * Both stay string params so `format=1` needs no change. The IMAGE output
     * wires to the sampler's initImage input; inpaint and reference images are
     * out of scope.
     */
    val imageInput = NodeType(
        name = IMAGE_INPUT,
        title = "图片输入",
        outputs = listOf(Port("image", "IMAGE")),
        widgets = listOf(
            Widget("path", "初始图", "text", ""),
            Widget("strength", "强度", "float", DEFAULT_IMG2IMG_STRENGTH),
        ),
    )

    val registry: Map<String, NodeType> =
        listOf(prompt, sdSampler, output, imageInput).associateBy { it.name }
}
