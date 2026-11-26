package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.desktop.graph.ConnectionResult
import com.abrah.nightmare.desktop.graph.DesktopNodeTypes
import com.abrah.nightmare.desktop.graph.Position
import com.abrah.nightmare.desktop.graph.WorkflowPersistence
import com.abrah.nightmare.desktop.graph.isSafeNodeId

/**
 * The narrow run capability an external tool needs from the GUI state (ADR-0008).
 * [DefaultModeState] implements it, so a future chat effort drives the same
 * [WorkflowSession] and 运行态 the shells render — no second copy of either.
 */
interface WorkflowRunControl {
    fun triggerRun(): Boolean
    fun runSnapshot(): RunSnapshot
}

/** One tool call in the future conversation surface; plain data, no LLM/provider types. */
sealed interface ToolCommand {
    data object NewWorkflow : ToolCommand

    data class AddNode(
        val id: String,
        val type: String,
        val x: Float = 0f,
        val y: Float = 0f,
        val params: Map<String, String> = emptyMap(),
    ) : ToolCommand

    data class SetParameter(val nodeId: String, val key: String, val value: String) : ToolCommand

    data class Connect(
        val fromNode: String,
        val toNode: String,
        val toPort: String,
        val fromPort: String? = null,
    ) : ToolCommand

    data object Run : ToolCommand
}

/** Observable outcome of one tool call; a rejected call leaves the workflow untouched. */
sealed interface ToolResult {
    data class Ok(val summary: String) : ToolResult
    data class Rejected(val reason: String) : ToolResult
}

/**
 * Command surface through which an external tool creates and edits the one
 * shared 工作流 and triggers the GUI-owned CLI run. It deliberately owns no
 * persistence format: nodes, wires and parameters go through the same graph
 * and sessions the default shell uses.
 */
class ToolInterface(
    private val session: WorkflowSession,
    private val runControl: WorkflowRunControl,
) {
    /** Convenience wiring: the state already owns the session and the run control. */
    constructor(state: DefaultModeState) : this(state.sharedSession, state)

    fun dispatch(command: ToolCommand): ToolResult = when (command) {
        is ToolCommand.NewWorkflow -> {
            session.replaceWorkflow(WorkflowPersistence.newWorkflow())
            ToolResult.Ok("新建空工作流")
        }

        is ToolCommand.AddNode -> {
            if (!isSafeNodeId(command.id)) {
                return ToolResult.Rejected("节点 id 不安全：${command.id}")
            }
            if (session.workflow.byId.containsKey(command.id)) {
                return ToolResult.Rejected("节点 id 已存在：${command.id}")
            }
            val type = DesktopNodeTypes.registry[command.type]
                ?: return ToolResult.Rejected("未知节点类型：${command.type}")
            val node = type.createNode(command.id, Position(command.x, command.y), command.params)
            session.replaceWorkflow(session.workflow.copy(nodes = session.workflow.nodes + node))
            ToolResult.Ok("已添加节点 ${command.id}")
        }

        is ToolCommand.SetParameter -> {
            // A rejected steps edit must explain the cap instead of masquerading
            // as a missing node/parameter, so the model can retry with a legal value.
            val stepsRejection = if (command.key == "steps") stepsRejectionReason(command.value) else null
            when {
                stepsRejection != null -> ToolResult.Rejected(stepsRejection)
                session.setNodeParameter(command.nodeId, command.key, command.value) ->
                    ToolResult.Ok("已设置 ${command.nodeId}.${command.key}")

                else -> ToolResult.Rejected("节点或参数不存在：${command.nodeId}.${command.key}")
            }
        }

        is ToolCommand.Connect -> when (val result = session.workflow.connect(
            command.fromNode,
            command.toNode,
            command.toPort,
            command.fromPort,
        )) {
            is ConnectionResult.Connected -> {
                session.replaceWorkflow(result.workflow)
                ToolResult.Ok("已连线 ${command.fromNode} -> ${command.toNode}:${command.toPort}")
            }

            is ConnectionResult.Rejected -> ToolResult.Rejected(result.reason)
        }

        is ToolCommand.Run -> when {
            runControl.runSnapshot().status == RunStatus.RUNNING ->
                ToolResult.Rejected("已有运行在进行中")

            runControl.triggerRun() -> {
                // triggerRun() blocks until the CLI child exits, so this is the
                // terminal 运行态 the result panel renders — a failure or a
                // cancel must not be reported to the model as a generated image.
                val final = runControl.runSnapshot()
                when (final.status) {
                    RunStatus.SUCCESS -> ToolResult.Ok("已触发运行并成功出图")
                    RunStatus.FAILURE, RunStatus.CANCELLED ->
                        ToolResult.Rejected("运行失败：${final.error ?: final.firstReason ?: final.status}")

                    else -> ToolResult.Rejected("运行失败：${final.status}")
                }
            }

            else -> ToolResult.Rejected("无法触发运行（运行可能已在进行中）")
        }
    }
}
