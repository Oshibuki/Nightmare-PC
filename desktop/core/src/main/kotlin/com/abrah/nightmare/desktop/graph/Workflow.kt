package com.abrah.nightmare.desktop.graph

import org.json.JSONArray
import org.json.JSONObject

/** Wire-format version written by the desktop GUI; readers must accept only this value. */
const val WORKFLOW_FORMAT = 1

/** One wire: an upstream node/port feeding a downstream node's input port. */
data class Connection(val from: Source, val toNode: String, val toPort: String)

/** Result of a wire gesture, observable without inspecting how the graph is stored. */
sealed interface ConnectionResult {
    data class Connected(val workflow: Workflow) : ConnectionResult
    data class Rejected(val reason: String, val workflow: Workflow) : ConnectionResult
}

/**
 * A 工作流: nodes, their parameter values, their wires and their canvas positions.
 * Pure JVM data only; persistence and execution live in other tickets.
 */
data class Workflow(val nodes: List<Node> = emptyList()) {
    val byId: Map<String, Node> get() = nodes.associateBy { it.id }

    /** All wires in the workflow, in node/port order. */
    val connections: List<Connection>
        get() = nodes.flatMap { node ->
            node.inputs.map { (port, source) -> Connection(source, node.id, port) }
        }

    /**
     * Serializes the shape consumed by 工作流 persistence: root `format`/`nodes`,
     * each node carrying id, type, params, incoming wires and canvas position.
     * Reading, validation and file I/O belong to the persistence ticket.
     */
    fun toJson(): String {
        val array = JSONArray()
        nodes.forEach { node ->
            val inputs = JSONObject()
            node.inputs.forEach { (port, source) -> inputs.put(port, source.toString()) }
            array.put(
                JSONObject()
                    .put("id", node.id)
                    .put("type", node.type)
                    .put("params", JSONObject(node.params))
                    .put("inputs", inputs)
                    .put(
                        "position",
                        JSONObject()
                            .put("x", node.position.x.toDouble())
                            .put("y", node.position.y.toDouble()),
                    ),
            )
        }
        return JSONObject().put("format", WORKFLOW_FORMAT).put("nodes", array).toString()
    }

    /**
     * Wires [fromNode]'s output into [toNode]'s [toPort]. A null [fromPort] picks
     * the source type's sole output. An illegal wire changes nothing and says why.
     */
    fun connect(
        fromNode: String,
        toNode: String,
        toPort: String,
        fromPort: String? = null,
    ): ConnectionResult {
        fun reject(reason: String) = ConnectionResult.Rejected(reason, this)

        val source = byId[fromNode] ?: return reject("source node not found: $fromNode")
        val target = byId[toNode] ?: return reject("target node not found: $toNode")
        if (fromNode == toNode) return reject("cannot connect a node to itself")

        val sourceType = DesktopNodeTypes.registry[source.type]
            ?: return reject("unknown node type for source: ${source.type}")
        val targetType = DesktopNodeTypes.registry[target.type]
            ?: return reject("unknown node type for target: ${target.type}")

        val output = when (fromPort) {
            null -> when (sourceType.outputs.size) {
                1 -> sourceType.outputs.single()
                0 -> return reject("no output port on $fromNode")
                else -> return reject("output port required for $fromNode")
            }
            else -> sourceType.outputs.firstOrNull { it.name == fromPort }
                ?: return reject("no output port $fromPort on $fromNode")
        }
        val input = targetType.inputs.firstOrNull { it.name == toPort }
            ?: return reject("unknown input port $toPort on $toNode")
        if (output.type != input.type) {
            return reject("port type mismatch: ${output.type} -> ${input.type}")
        }

        val rewired = target.copy(inputs = target.inputs + (toPort to Source(fromNode, output.name)))
        return ConnectionResult.Connected(
            copy(nodes = nodes.map { if (it.id == toNode) rewired else it }),
        )
    }
}
