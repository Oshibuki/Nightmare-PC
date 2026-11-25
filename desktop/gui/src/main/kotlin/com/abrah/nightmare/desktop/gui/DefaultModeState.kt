package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.desktop.graph.ConnectionResult
import com.abrah.nightmare.desktop.graph.DesktopNodeTypes
import com.abrah.nightmare.desktop.graph.Node
import com.abrah.nightmare.desktop.graph.NodeType
import com.abrah.nightmare.desktop.graph.Widget
import com.abrah.nightmare.desktop.graph.Position
import com.abrah.nightmare.desktop.graph.Workflow

/** One chip in the default mode's top workflow rail. */
data class ChainChip(val nodeId: String, val nodeType: String, val title: String, val summary: String)

/** One editable row in the parameter drawer. Values are always strings on the wire. */
data class ParameterField(
    val key: String,
    val label: String,
    val kind: String,
    val value: String,
    val options: List<String> = emptyList(),
)

/** Synthetic preview shown until a run produces a real PNG. */
data class PreviewInfo(val title: String, val detail: String)

/** Everything the default shell renders, derived from the shared workflow on demand. */
data class DefaultModeSnapshot(
    val chips: List<ChainChip>,
    val selectedNodeId: String?,
    val fields: List<ParameterField>,
    val preview: PreviewInfo,
)

/**
 * The single 工作流 holder shared by every interaction mode. Advanced mode and
 * external tools can replace or edit the workflow here; the default shell only
 * reads it, so a mode switch never creates a second chain (ADR-0008).
 */
class WorkflowSession(initialWorkflow: Workflow = builtInTxt2imgWorkflow()) {
    private val listeners = mutableListOf<() -> Unit>()

    var workflow: Workflow = initialWorkflow
        private set

    /** Returns an unsubscribe function so UI listeners do not outlive the screen. */
    fun addChangeListener(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun replaceWorkflow(next: Workflow) {
        workflow = next
        listeners.toList().forEach { it() }
    }

    fun setNodeParameter(nodeId: String, key: String, value: String): Boolean {
        val node = workflow.byId[nodeId] ?: return false
        if (!node.params.containsKey(key)) return false
        replaceWorkflow(
            workflow.copy(
                nodes = workflow.nodes.map { current ->
                    if (current.id == nodeId) {
                        current.copy(params = current.params + (key to value))
                    } else {
                        current
                    }
                },
            ),
        )
        return true
    }
}

/** Default mode (chain rail + parameter drawer) state over one [WorkflowSession]. */
class DefaultModeState(
    private val session: WorkflowSession = WorkflowSession(),
    private val runner: WorkflowRunner = WorkflowRunner(),
) : WorkflowRunControl {
    private val listeners = mutableListOf<() -> Unit>()
    private var selectedNodeId: String? = session.workflow.nodes.firstOrNull()?.id

    init {
        session.addChangeListener { notifyChanged() }
        runner.addChangeListener { notifyChanged() }
    }

    /** The one workflow all interaction modes operate on. */
    val workflow: Workflow get() = session.workflow

    /** The shared holder an external tool drives; keep one chain, not a second copy. */
    val sharedSession: WorkflowSession get() = session

    /** Returns an unsubscribe function for shells that need to recompose on edits. */
    fun addChangeListener(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun snapshot(): DefaultModeSnapshot {
        val workflow = session.workflow
        val nodes = workflow.nodes
        val selected = nodes.firstOrNull { it.id == selectedNodeId } ?: nodes.firstOrNull()
        return DefaultModeSnapshot(
            chips = nodes.map { it.toChip() },
            selectedNodeId = selected?.id,
            fields = selected?.let { fieldsFor(it) } ?: emptyList(),
            preview = syntheticPreview(workflow),
        )
    }

    fun selectNode(nodeId: String): Boolean {
        if (session.workflow.byId[nodeId] == null) return false
        selectedNodeId = nodeId
        notifyChanged()
        return true
    }

    fun setSelectedParameter(key: String, value: String): Boolean {
        val current = snapshot()
        val id = current.selectedNodeId ?: return false
        if (current.fields.none { it.key == key }) return false
        return session.setNodeParameter(id, key, value)
    }

    /**
     * Runs the shared workflow as a stateless CLI child process. Returns false
     * when a run is already in flight, so the shell can disable 「运行」.
     */
    override fun triggerRun(): Boolean = runner.run(session.workflow)

    /** One immutable read of 运行态 for the shell and external tools. */
    override fun runSnapshot(): RunSnapshot = runner.snapshot()

    /** Live child handle, so window-close cleanup can end an active run. */
    fun runHandle(): Process? = runner.processHandle()

    private fun notifyChanged() {
        listeners.toList().forEach { it() }
    }

    private fun fieldsFor(node: Node): List<ParameterField> {
        val type = DesktopNodeTypes.registry[node.type] ?: return emptyList()
        return type.widgets.map { widget ->
            ParameterField(
                key = widget.name,
                label = widget.label,
                kind = widget.kind,
                value = node.params[widget.name] ?: widget.default.orEmpty(),
                options = choicesFor(widget),
            )
        }
    }

    private fun choicesFor(widget: Widget): List<String> = when (widget.name) {
        "sampler" -> listOf("euler", "euler_a", "dpmpp_2m", "ddim")
        "scheduler" -> listOf("normal", "karras", "exponential", "simple")
        "save" -> listOf("true", "false")
        else -> emptyList()
    }

    private fun Node.toChip(): ChainChip {
        val type: NodeType? = DesktopNodeTypes.registry[this.type]
        return ChainChip(
            nodeId = id,
            nodeType = this.type,
            title = type?.title ?: id,
            summary = chipSummary(this),
        )
    }

    private fun chipSummary(node: Node): String = when (node.type) {
        DesktopNodeTypes.PROMPT -> listOfNotNull(
            node.params["prompt"]?.takeIf { it.isNotBlank() },
            node.params["negative"]?.takeIf { it.isNotBlank() }?.let { "负向: $it" },
        ).joinToString(" · ")

        DesktopNodeTypes.SD_SAMPLER -> "${node.params["sampler"].orEmpty()} · ${node.params["steps"].orEmpty()} steps"
        DesktopNodeTypes.OUTPUT -> "save=${node.params["save"].orEmpty()}"
        else -> node.params.values.take(2).joinToString(" · ")
    }

    private fun syntheticPreview(workflow: Workflow): PreviewInfo {
        val sampler = workflow.nodes.firstOrNull { it.type == DesktopNodeTypes.SD_SAMPLER }
        val width = sampler?.params?.get("width").orEmpty()
        val height = sampler?.params?.get("height").orEmpty()
        val seed = sampler?.params?.get("seed").orEmpty()
        return PreviewInfo(
            title = "合成预览（尚未运行引擎）",
            detail = listOfNotNull(
                "$width×$height".takeIf { width.isNotBlank() || height.isNotBlank() },
                "seed $seed".takeIf { seed.isNotBlank() },
            ).joinToString(" · "),
        )
    }
}

/** A ready-to-edit txt2img 工作流: prompt → SD sampler → output, with no engine dependency. */
fun builtInTxt2imgWorkflow(): Workflow {
    val prompt = DesktopNodeTypes.prompt.createNode(
        id = "prompt",
        position = Position(0f, 0f),
        params = mapOf("prompt" to "a quiet mountain lake at dawn"),
    )
    val sampler = DesktopNodeTypes.sdSampler.createNode("sampler", Position(260f, 0f))
    val output = DesktopNodeTypes.output.createNode("output", Position(520f, 0f))
    val wired = Workflow(listOf(prompt, sampler, output))
        .connect("prompt", "sampler", "prompt") as? ConnectionResult.Connected
        ?: error("built-in prompt wire was rejected")
    val final = wired.workflow.connect("sampler", "output", "image") as? ConnectionResult.Connected
        ?: error("built-in output wire was rejected")
    return final.workflow
}
