package com.abrah.nightmare.desktop.canvas

import com.abrah.nightmare.desktop.graph.DesktopNodeTypes
import com.abrah.nightmare.desktop.graph.Node
import com.abrah.nightmare.desktop.graph.NodeType
import com.abrah.nightmare.desktop.graph.Source
import com.abrah.nightmare.desktop.graph.Workflow

/**
 * Desktop-authored glue (not a mobile copy). The copied [CanvasModel] and
 * [CanvasState] speak the small vocabulary their geometry needs; ADR-0007 says
 * the desktop registry carries presentation only, so the mobile executor's
 * constants and size rules are deliberately NOT brought over. Members below
 * are the narrow bridge from the copied code to ticket 11's desktop graph.
 */

/** The registry the copied gesture code reads. Presentation-only, three txt2img types. */
val NODE_TYPES: Map<String, NodeType> = DesktopNodeTypes.registry

/** The compact `port` → `"node[:port]"` spelling used by workflows and fixtures. */
fun sources(vararg wires: Pair<String, String>): Map<String, Source> =
    wires.associate { (port, from) -> port to Source.parse(from) }

/** Widgets whose value is prose shown on the node body (mobile `NodeType.prose`). */
val NodeType.prose: List<String>
    get() = widgets.filter { it.kind == "text" }.map { it.name }

/** A preview is a picture to frame, not a result to open (mobile default: false). */
val NodeType.interactive: Boolean
    get() = name == "image.crop" || name == "image.mask"

/** Desktop ids come from `DesktopNodeTypes`, so there is no extra default id. */
val NodeType.defaultId: String?
    get() = null

internal const val PORTRAIT_ANIMATE_TYPE = "portrait.animate"
internal const val WAN_FUN_TYPE = "nd.wan_fun"
internal const val OUTPUT_TYPE = "core.output"
internal const val UPSCALER_PARAM = "upscaler"
internal const val LORAS_PARAM = "loras"
internal const val MASK_OPS_PARAM = "ops"
internal const val ADD_OBJECTS_PARAM = "objects"
internal const val ADD_OBJECTS_ORIGINAL = "original"
internal const val ADD_OBJECTS_LAYER = "layer"
internal const val ADD_OBJECTS_RING = "ring"
internal const val BATCH_PREFIX = "batch_"
internal const val FIX_SEED_PARAM = "fix_seed"

/** Extra reference ports are a video/FLUX concern; the txt2img slice has none. */
internal val EXTRA_REFS: List<String> = emptyList()

internal fun isSwapType(type: String): Boolean = false
internal fun isSampler(type: String): Boolean = type == DesktopNodeTypes.SD_SAMPLER || type == "sd15.sample" || type == "sd15.inpaint"

/** No token budget is enforced by a presentation-only node type. */
class PromptTokens {
    data class Count(val used: Int, val max: Int)
}

internal fun promptTokenBudget(): Int = Int.MAX_VALUE
internal fun promptTokenCount(value: String, budget: Int): PromptTokens.Count? = null

/**
 * The one size rule the canvas can check without an executor: a bare picture
 * cannot promise the size an encoder demands, so the wire is refused and the
 * fix (a Crop node) is named. The rest of Framing.kt's size derivation stays
 * with the CLI. Copied in substance from app/.../Framing.kt:282 (ADR-0001).
 */
internal fun sizeRefusal(
    graph: Workflow,
    types: Map<String, NodeType>,
    fromNode: String,
    toNode: String,
    toPort: String,
): String? {
    val producer = graph.byId[fromNode] ?: return null
    val consumer = graph.byId[toNode] ?: return null
    if (producer.type != "core.image" || consumer.type != "sd.vae_encode" || toPort != "image") return null
    val w = consumer.params["width"] ?: "?"
    val h = consumer.params["height"] ?: "?"
    return "$toNode needs ${w}x${h} and $fromNode cannot promise a size. Put a Crop between them"
}

/** The desktop GUI does not own a model catalogue (that is CLI/engine territory). */
data class DesktopModelSpec(
    val steps: Int = 20,
    val cfg: Double = 7.0,
    val scheduler: String = "normal",
    val starterPrompt: String = "",
    val starterNegative: String = "",
)

internal fun modelById(id: String): DesktopModelSpec? = null
internal fun videoPromptsFor(node: Node, graph: Workflow): String? = null

/** The saved viewport shape. The wire format is ticket 16's; this is the in-memory value. */
data class SavedView(
    val offset: Pt,
    val scale: Float,
    val zoomLocked: Boolean = false,
    val panLocked: Boolean = false,
)

/** What the canvas draws for a wire in flight. */
data class PendingWire(val from: PortRef, val to: Pt, val error: String? = null)

// ---- structural graph operations the copied state layer calls -------------
// All are pure rewrites of the desktop Workflow's node list; no executor, cache
// or backend handle is involved (ADR-0007).

private fun Workflow.rewriteNode(id: String, change: (Node) -> Node): Workflow =
    copy(nodes = nodes.map { if (it.id == id) change(it) else it })

internal fun Workflow.withParam(nodeId: String, name: String, value: String): Workflow =
    rewriteNode(nodeId) { it.copy(params = it.params + (name to value)) }

internal fun Workflow.withParams(nodeId: String, values: Map<String, String>): Workflow =
    rewriteNode(nodeId) { it.copy(params = it.params + values) }

internal fun Workflow.freeId(base: String): String {
    if (base !in byId) return base
    var n = 2
    while ("${base}_$n" in byId) n++
    return "${base}_$n"
}

internal fun Workflow.connected(toNode: String, port: String, from: Source): Workflow {
    val was = byId[toNode]?.inputs?.get(port)
    val wired = rewriteNode(toNode) { it.copy(inputs = it.inputs + (port to from)) }
    // A DIFFERENT picture source forgets framing/painting made on the old one;
    // re-dropping the same wire must not wipe a painted mask.
    if (port !in PICTURE_PORTS || was == null || was == from) return wired
    return wired.forgetPictureWork { it == toNode || it in wired.descendantsOf(toNode) }
}

/** Convenience for the sole-output case, kept for the migrated tests. */
internal fun Workflow.connected(toNode: String, port: String, fromNode: String): Workflow =
    connected(toNode, port, Source(fromNode))

// Copied from app/src/main/java/com/abrah/nightmare/canvas/CanvasScreen.kt on
// 2026-11-26 (ADR-0001): the readable zoom readout, pure and Compose-free.
internal fun zoomLabel(scale: Float): String =
    if (scale >= 0.245f) "%.1fx".format(java.util.Locale.ROOT, scale)
    else "%.2f".format(java.util.Locale.ROOT, scale).trimEnd('0') + "x"

internal fun Workflow.disconnected(toNode: String, port: String): Workflow {
    val had = byId[toNode]?.inputs?.containsKey(port) == true
    val cut = rewriteNode(toNode) { it.copy(inputs = it.inputs - port) }
    if (!had || port !in PICTURE_PORTS) return cut
    return cut.forgetPictureWork { it == toNode || it in cut.descendantsOf(toNode) }
}

internal fun Workflow.dependsOn(node: String, maybeUpstream: String): Boolean {
    val seen = mutableSetOf<String>()
    val stack = ArrayDeque(byId[node]?.inputs?.values?.map { it.node }.orEmpty())
    while (stack.isNotEmpty()) {
        val next = stack.removeLast()
        if (next == maybeUpstream) return true
        if (!seen.add(next)) continue
        byId[next]?.inputs?.values?.forEach { stack.addLast(it.node) }
    }
    return false
}

internal fun Workflow.wouldCycle(fromNode: String, toNode: String): Boolean =
    fromNode == toNode || dependsOn(fromNode, toNode)

internal fun Workflow.without(nodeId: String): Workflow {
    val readers = nodes.filter { it.inputs["image"]?.node == nodeId }.map { it.id }.toSet()
    val base = if (readers.isEmpty()) this else forgetPictureWork { id ->
        id in readers || readers.any { r -> dependsOn(id, r) }
    }
    return base.copy(
        nodes = base.nodes
            .filterNot { it.id == nodeId }
            .map { it.copy(inputs = it.inputs.filterValues { up -> up.node != nodeId }) },
    )
}

/** Rewiring a picture drops framing/mask values made on the old picture. */
private val PICTURE_WORK_KEYS = setOf("x", "y", "w", "h", "locked", "ops", "grow", "feather")
private val PICTURE_PORTS = setOf("image", "reference")

private fun Workflow.forgetPictureWork(select: (String) -> Boolean): Workflow = copy(
    nodes = nodes.map { n ->
        if (!select(n.id)) n else n.copy(params = n.params - PICTURE_WORK_KEYS)
    },
)

private fun Workflow.descendantsOf(id: String): Set<String> {
    val found = mutableSetOf<String>()
    nodes.forEach { n -> if (n.inputs.values.any { it.node == id }) found += n.id }
    return found
}

internal fun Workflow.switchingOffFor(nodeId: String, port: String): Workflow = this

internal fun Workflow.switchingOnFor(nodeId: String, port: String): Workflow = this

internal fun Workflow.switchingOffAfterRemoving(before: Workflow, removed: String): Workflow = this

internal fun Workflow.withNewPicture(nodeId: String, uri: String): Workflow {
    val base = withParam(nodeId, "uri", uri)
    return base.forgetPictureWork { it == nodeId || it in base.descendantsOf(nodeId) }
}
