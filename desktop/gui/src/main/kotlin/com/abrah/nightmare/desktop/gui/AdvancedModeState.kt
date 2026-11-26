package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.desktop.canvas.CanvasState
import com.abrah.nightmare.desktop.canvas.NODE_TYPES
import com.abrah.nightmare.desktop.canvas.Pt
import com.abrah.nightmare.desktop.canvas.Viewport
import com.abrah.nightmare.desktop.canvas.Workflow as CanvasWorkflow
import com.abrah.nightmare.desktop.canvas.layout
import com.abrah.nightmare.desktop.canvas.wires
import com.abrah.nightmare.desktop.graph.DesktopNodeTypes
import com.abrah.nightmare.desktop.graph.Node
import com.abrah.nightmare.desktop.graph.NodeType
import com.abrah.nightmare.desktop.graph.Position
import com.abrah.nightmare.desktop.graph.Source
import com.abrah.nightmare.desktop.graph.Widget
import com.abrah.nightmare.desktop.graph.Workflow as GraphWorkflow

/** One visible socket on an advanced-mode node, including its world position. */
data class AdvancedPortView(
    val name: String,
    val type: String,
    val isInput: Boolean,
    val x: Float,
    val y: Float,
)

/** One drawn node in 原型 A's free canvas. */
data class AdvancedNodeView(
    val id: String,
    val type: String,
    val title: String,
    val summary: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val ports: List<AdvancedPortView>,
    val selected: Boolean,
)

/** One wire with the same endpoints the copied geometry layer draws. */
data class AdvancedWireView(
    val fromNode: String,
    val fromPort: String,
    val toNode: String,
    val toPort: String,
    val from: Pt,
    val to: Pt,
)

/** A wire in flight, so the shell can colour a refusal before the user drops. */
data class AdvancedPendingView(
    val fromNode: String,
    val fromPort: String,
    val x: Float,
    val y: Float,
    val error: String?,
)

/** Everything the advanced shell renders, derived from the one shared 工作流. */
data class AdvancedModeSnapshot(
    val nodes: List<AdvancedNodeView>,
    val wires: List<AdvancedWireView>,
    val selectedNodeId: String?,
    val fields: List<ParameterField>,
    val message: String?,
    val pending: AdvancedPendingView?,
    val viewport: Viewport,
)

/** Observable result of one port-to-port drag, including the refusal reason. */
data class WireFeedback(val connected: Boolean, val message: String?)

/**
 * 高级模式 (free canvas) state over the SAME [WorkflowSession] and runner as
 * 默认模式. The copied [CanvasState] owns gestures and viewport; this class is
 * the thin bridge that writes its graph back into the shared session, so a mode
 * switch only changes the shell and never forks the 工作流 (ADR-0007/0008).
 */
class AdvancedModeState(
    private val session: WorkflowSession = WorkflowSession(),
    private val runner: WorkflowRunner = WorkflowRunner(),
) : WorkflowRunControl {
    private val listeners = mutableListOf<() -> Unit>()
    private var canvas = newCanvasState(session.workflow)
    private var applyingSessionChange = false

    init {
        session.addChangeListener {
            // A tool or the default shell replaced the one workflow. Rebuild the
            // canvas from it, keeping the camera and the open properties panel.
            if (applyingSessionChange) return@addChangeListener
            val graph = session.workflow
            canvas = newCanvasState(graph).copy(
                viewport = canvas.viewport,
                editing = canvas.editing?.takeIf { graph.byId.containsKey(it) }
                    ?: graph.nodes.firstOrNull()?.id,
                selection = canvas.selection.filter { graph.byId.containsKey(it) }.toSet(),
                multiSelect = canvas.multiSelect,
                message = canvas.message,
                zoomLocked = canvas.zoomLocked,
                panLocked = canvas.panLocked,
            )
            notifyChanged()
        }
        runner.addChangeListener { notifyChanged() }
    }

    /** The one workflow holder; both shells and external tools share it. */
    val sharedSession: WorkflowSession get() = session

    /** Read-only view of the shared 工作流 for shells and tests. */
    val workflow: GraphWorkflow get() = session.workflow

    /** Returns an unsubscribe function for shells that recompose on edits. */
    fun addChangeListener(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun snapshot(): AdvancedModeSnapshot {
        val boxes = layout(canvas.workflow, NODE_TYPES)
        val selected = canvas.editing?.takeIf { session.workflow.byId.containsKey(it) }
            ?: session.workflow.nodes.firstOrNull()?.id
        val selectedNode = selected?.let { session.workflow.byId[it] }
        return AdvancedModeSnapshot(
            nodes = boxes.map { box ->
                AdvancedNodeView(
                    id = box.id,
                    type = box.node.type,
                    title = DesktopNodeTypes.registry[box.node.type]?.title ?: box.node.type,
                    summary = summaryFor(box.node),
                    x = box.topLeft.x,
                    y = box.topLeft.y,
                    width = box.width,
                    height = box.height,
                    ports = box.inputs.mapIndexed { i, port ->
                        val at = box.inputPort(i)
                        AdvancedPortView(port.name, port.type, true, at.x, at.y)
                    } + box.outputs.mapIndexed { i, port ->
                        val at = box.outputPort(i)
                        AdvancedPortView(port.name, port.type, false, at.x, at.y)
                    },
                    selected = box.id == selected,
                )
            },
            wires = wires(boxes).map { (ref, ends) ->
                AdvancedWireView(
                    fromNode = ref.from.node,
                    fromPort = ref.from.port ?: soleOutput(ref.from.node),
                    toNode = ref.toNode,
                    toPort = ref.toPort,
                    from = ends.first,
                    to = ends.second,
                )
            },
            selectedNodeId = selected,
            fields = selectedNode?.let { fieldsFor(it) }.orEmpty(),
            message = canvas.message,
            pending = canvas.pending?.let { wire ->
                AdvancedPendingView(
                    fromNode = wire.from.nodeId,
                    fromPort = wire.from.port.name,
                    x = wire.to.x,
                    y = wire.to.y,
                    error = wire.error,
                )
            },
            viewport = canvas.viewport,
        )
    }

    /** Adds one of the three registered 节点类型 from the palette. */
    fun addNode(type: String): Boolean {
        val nodeType = DesktopNodeTypes.registry[type] ?: return false
        val count = session.workflow.nodes.size
        val at = Pt(60f + (count % 3) * 260f, 80f + (count / 3) * 220f)
        apply(canvas.addNode(nodeType, at))
        return true
    }

    /** Opens a node's properties in the right-hand panel. */
    fun selectNode(nodeId: String): Boolean {
        if (session.workflow.byId[nodeId] == null) return false
        canvas = canvas.copy(editing = nodeId, focusField = null, message = null)
        notifyChanged()
        return true
    }

    /** Edits a widget of the node shown in the properties panel. */
    fun setSelectedParameter(key: String, value: String): Boolean {
        val nodeId = canvas.editing ?: session.workflow.nodes.firstOrNull()?.id ?: return false
        val node = session.workflow.byId[nodeId] ?: return false
        val type = DesktopNodeTypes.registry[node.type] ?: return false
        if (type.widgets.none { it.name == key }) return false
        // The canvas owns layout/wiring, but the steps cap is a run-safety rule:
        // route edits through the same validation the default mode and tools use.
        if (key == "steps" && stepsRejectionReason(value) != null) return false
        apply(canvas.setParam(nodeId, key, value))
        return true
    }

    /**
     * The public form of a user's wire drag. It drives the copied gesture state
     * machine at the two port centres, so the canvas and this method cannot
     * disagree about what is connectable or why a wire was refused.
     */
    fun dragWire(fromNode: String, fromPort: String, toNode: String, toPort: String): WireFeedback {
        val boxes = layout(canvas.workflow, NODE_TYPES)
        val fromIsOutput = isOutputPort(fromNode, fromPort)
        val toIsOutput = isOutputPort(toNode, toPort)
        val fromAt = portPoint(boxes, fromNode, fromPort, fromIsOutput)
        val toAt = portPoint(boxes, toNode, toPort, toIsOutput)
        if (fromAt == null || toAt == null) {
            val message = "找不到端口：$fromNode:$fromPort -> $toNode:$toPort"
            canvas = canvas.copy(message = message)
            notifyChanged()
            return WireFeedback(false, message)
        }

        val pressed = canvas.press(fromAt, NODE_TYPES)
        if (pressed.pending == null) {
            val message = "起点不是可连线端口：$fromNode:$fromPort"
            canvas = canvas.copy(message = message)
            notifyChanged()
            return WireFeedback(false, message)
        }
        val dragged = pressed.drag(toAt, Pt(0f, 0f), NODE_TYPES)
        val released = dragged.release(toAt, NODE_TYPES)
        apply(released)

        val (inputNode, inputPort, outputNode, outputPort) = if (fromIsOutput && !toIsOutput) {
            WireEnds(toNode, toPort, fromNode, fromPort)
        } else if (!fromIsOutput && toIsOutput) {
            WireEnds(fromNode, fromPort, toNode, toPort)
        } else {
            WireEnds("", "", "", "")
        }
        val connected = inputNode.isNotEmpty() &&
            session.workflow.byId[inputNode]?.inputs?.get(inputPort) == Source(outputNode, outputPort)
        val message = if (connected) {
            "已连接 $outputNode:$outputPort -> $inputNode:$inputPort"
        } else {
            released.message ?: "连线未完成"
        }
        return WireFeedback(connected, message)
    }

    /** Raw pointer down, forwarded to the copied gesture state machine. */
    fun pressWorld(world: Pt) {
        canvas = canvas.press(world, NODE_TYPES)
        notifyChanged()
    }

    /** Raw pointer move; [screenDelta] is in pixels, [world] in world units. */
    fun dragWorld(world: Pt, screenDelta: Pt) {
        canvas = canvas.drag(world, screenDelta, NODE_TYPES)
        notifyChanged()
    }

    /** Raw pointer up; a completed wire or node drag is written to the session. */
    fun releaseWorld(world: Pt) {
        apply(canvas.release(world, NODE_TYPES))
    }

    /** The camera the pointer layer must convert screen coordinates with. */
    fun currentViewport(): Viewport = canvas.viewport

    /** Wheel zoom about a screen-space focus, clamped by the copied viewport. */
    fun zoomAt(focus: Pt, factor: Float) {
        canvas = canvas.zoom(focus, factor)
        notifyChanged()
    }

    /** Pan by a screen-space delta. */
    fun panBy(delta: Pt) {
        canvas = canvas.pan(delta)
        notifyChanged()
    }

    override fun triggerRun(): Boolean = runner.run(session.workflow)

    override fun runSnapshot(): RunSnapshot = runner.snapshot()

    /** Live child handle, so window close can end an active run. */
    fun runHandle(): Process? = runner.processHandle()

    /** Cancel the run through its owning runner, not by killing a bare Process. */
    fun cancelRun(): Boolean = WorkflowRunner.cancelActiveProcess(runHandle())

    /** Explicit save of the one shared workflow; the shell shows the feedback. */
    fun saveWorkflow(path: String): PersistenceFeedback = session.saveTo(path)

    /** Explicit load: Ok replaces the shared workflow, Err only reports the error. */
    fun loadWorkflow(path: String): PersistenceFeedback = session.loadFrom(path)

    // ---- bridge between the graph's stored position and the canvas map -----

    private fun newCanvasState(graph: GraphWorkflow): CanvasState = CanvasState(
        workflow = CanvasWorkflow(
            graph = graph,
            positions = graph.nodes.associate { it.id to Pt(it.position.x, it.position.y) },
        ),
        editing = graph.nodes.firstOrNull()?.id,
    )

    private fun graphFromCanvas(state: CanvasState): GraphWorkflow {
        val graph = state.workflow.graph
        return graph.copy(
            nodes = graph.nodes.map { node ->
                state.workflow.positions[node.id]?.let { at ->
                    node.copy(position = Position(at.x, at.y))
                } ?: node
            },
        )
    }

    private fun apply(next: CanvasState) {
        canvas = next
        val graph = graphFromCanvas(next)
        if (graph != session.workflow) {
            applyingSessionChange = true
            try {
                session.replaceWorkflow(graph)
            } finally {
                applyingSessionChange = false
            }
        }
        notifyChanged()
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it() }
    }

    private fun isOutputPort(nodeId: String, port: String): Boolean =
        session.workflow.byId[nodeId]?.type
            ?.let { DesktopNodeTypes.registry[it]?.outputs?.any { p -> p.name == port } }
            ?: false

    private fun portPoint(
        boxes: List<com.abrah.nightmare.desktop.canvas.NodeBox>,
        nodeId: String,
        port: String,
        isOutput: Boolean,
    ): Pt? {
        val box = boxes.firstOrNull { it.id == nodeId } ?: return null
        val ports = if (isOutput) box.outputs else box.inputs
        val index = ports.indexOfFirst { it.name == port }.takeIf { it >= 0 } ?: return null
        return if (isOutput) box.outputPort(index) else box.inputPort(index)
    }

    private fun soleOutput(nodeId: String): String =
        session.workflow.byId[nodeId]?.type
            ?.let { DesktopNodeTypes.registry[it]?.outputs?.singleOrNull()?.name }
            .orEmpty()

    private fun summaryFor(node: Node): String = when (node.type) {
        DesktopNodeTypes.PROMPT -> listOfNotNull(
            node.params["prompt"]?.takeIf { it.isNotBlank() },
            node.params["negative"]?.takeIf { it.isNotBlank() }?.let { "负向: $it" },
        ).joinToString(" · ")

        DesktopNodeTypes.SD_SAMPLER ->
            "${node.params["sampler"].orEmpty()} · ${node.params["steps"].orEmpty()} steps"

        DesktopNodeTypes.OUTPUT -> "save=${node.params["save"].orEmpty()}"
        DesktopNodeTypes.IMAGE_INPUT -> "强度 ${node.params["strength"].orEmpty()}"
        else -> node.params.values.take(2).joinToString(" · ")
    }

    private fun fieldsFor(node: Node): List<ParameterField> {
        val type: NodeType = DesktopNodeTypes.registry[node.type] ?: return emptyList()
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

    private data class WireEnds(
        val inputNode: String,
        val inputPort: String,
        val outputNode: String,
        val outputPort: String,
    )
}
