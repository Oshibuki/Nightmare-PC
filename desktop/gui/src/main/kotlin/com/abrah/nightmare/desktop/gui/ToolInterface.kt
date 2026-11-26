package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.desktop.core.ModelFile.Contents
import com.abrah.nightmare.desktop.core.ModelLibrary
import com.abrah.nightmare.desktop.graph.ConnectionResult
import com.abrah.nightmare.desktop.graph.DesktopNodeTypes
import com.abrah.nightmare.desktop.graph.Position
import com.abrah.nightmare.desktop.graph.WorkflowPersistence
import com.abrah.nightmare.desktop.graph.isSafeNodeId
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

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

    /** Mobile's name for the same empty-workflow reset as [NewWorkflow]. */
    data object NewFlow : ToolCommand

    /** Read the shared 工作流 back, so a model can see what its edits produced. */
    data object GetCanvas : ToolCommand

    /** Save the workflow; an existing target needs a confirmation 结构化追问 (ADR-0015). */
    data class SaveFlow(val path: String) : ToolCommand

    /** [dir] overrides $NIGHTMARE_MODELS / the shared cache for one call. */
    data class ListModels(val dir: String? = null) : ToolCommand

    /** Read-only LoRA enumeration; no search, download or delete (ADR-0014). */
    data class ListLoras(val dir: String? = null) : ToolCommand

    /**
     * Delete one LoRA below the model root's lora tree. [dir] overrides the
     * shared library root for one call, like [ListLoras].
     */
    data class DeleteLora(val path: String, val dir: String? = null) : ToolCommand

    /** Download a LoRA from [url] into the shared library's lora tree. */
    data class DownloadLora(val url: String, val dir: String? = null) : ToolCommand
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
    /**
     * Fallback confirm seam for callers that dispatch directly (e.g. the replay
     * reset). Confirmation tools treat anything but their affirmative option as
     * a cancellation, so this default can never overwrite or delete by itself.
     * Production goes through [DesktopToolBox], which passes its injected ask.
     */
    private val defaultAsk: (String, List<String>) -> String = { _, _ -> AskGate.CANCEL },
    /** Injected for JVM tests; production uses bounded HttpURLConnection (ADR-0015). */
    private val downloader: LoraDownloader = HttpLoraDownloader,
    /** Sources at or above this size need a confirmation 结构化追问 (mobile's 512 MB rule). */
    private val largeFileBytes: Long = DEFAULT_LARGE_LORA_BYTES,
) {
    /** Convenience wiring: the state already owns the session and the run control. */
    constructor(state: DefaultModeState) : this(state.sharedSession, state)

    fun dispatch(command: ToolCommand): ToolResult = dispatch(command, defaultAsk)

    /**
     * One tool call at the state seam. [ask] is the confirmation-type 结构化追问
     * function injected at the GUI layer (ADR-0015); it is only consulted by
     * tools with a side effect the person must approve.
     */
    fun dispatch(command: ToolCommand, ask: (String, List<String>) -> String): ToolResult = when (command) {
        is ToolCommand.NewWorkflow, is ToolCommand.NewFlow -> {
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

        is ToolCommand.GetCanvas -> ToolResult.Ok(session.workflow.toJson())

        is ToolCommand.SaveFlow -> {
            val existing = command.path.trim().let { runCatching { Path.of(it) }.getOrNull() }
            if (existing != null && Files.exists(existing)) {
                // Only the affirmative option overwrites. The person's 取消, an
                // AskGate CANCEL and a timeout all land on the same safe branch.
                val answer = ask(SAVE_OVERWRITE_QUESTION, listOf(SAVE_OPTION, CANCEL_OPTION))
                if (answer != SAVE_OPTION) return ToolResult.Ok("已取消覆盖保存：$existing")
            }
            val feedback = session.saveTo(command.path)
            if (feedback.ok) ToolResult.Ok(feedback.message) else ToolResult.Rejected(feedback.message)
        }

        is ToolCommand.ListModels -> modelInventory(command.dir)

        is ToolCommand.ListLoras -> loraInventory(command.dir)

        is ToolCommand.DeleteLora -> deleteLora(command, ask)

        is ToolCommand.DownloadLora -> downloadLora(command, ask)
    }

    /**
     * One library scan shared by `list_models` and `list_loras`: the CLI's
     * resolve/scan rules, never a second model-discovery implementation.
     * A missing root stays an explicit rejection rather than an empty list.
     */
    private fun scanLibrary(dir: String?): ModelLibrary.Scan =
        ModelLibrary.scan(ModelLibrary.resolve(dir, System.getenv()))

    private fun modelInventory(dir: String?): ToolResult {
        val scan = try {
            scanLibrary(dir)
        } catch (e: IOException) {
            return ToolResult.Rejected("无法读取模型库：${e.message}")
        }
        val models = JSONArray()
        scan.found.forEach { found ->
            models.put(
                JSONObject()
                    .put("path", found.path.toString())
                    .put("bytes", found.bytes)
                    .put(
                        "format",
                        when (found.contents) {
                            is Contents.Safetensors -> "safetensors"
                            is Contents.Gguf -> "gguf"
                        },
                    )
                    .put("tensors", found.contents.tensors)
                    .put("dtypes", JSONArray(found.contents.dtypes.toList().sorted())),
            )
        }
        val rejected = JSONArray()
        scan.rejected.forEach { rejected.put(JSONObject().put("path", it.path.toString()).put("reason", it.reason)) }
        return ToolResult.Ok(
            JSONObject()
                .put("root", scan.root.toString())
                .put("models", models)
                .put("rejected", rejected)
                .toString(),
        )
    }

    private fun loraInventory(dir: String?): ToolResult {
        val scan = try {
            scanLibrary(dir)
        } catch (e: IOException) {
            return ToolResult.Rejected("无法读取模型库：${e.message}")
        }
        val loras = JSONArray()
        scan.found.filter { isLoraFile(it.path, scan.root) }.forEach { found ->
            loras.put(
                JSONObject()
                    .put("path", found.path.toString())
                    .put("bytes", found.bytes)
                    .put("tensors", found.contents.tensors),
            )
        }
        val rejected = JSONArray()
        scan.rejected.filter { isLoraFile(it.path, scan.root) }.forEach {
            rejected.put(JSONObject().put("path", it.path.toString()).put("reason", it.reason))
        }
        return ToolResult.Ok(
            JSONObject()
                .put("root", scan.root.toString())
                .put("loras", loras)
                .put("rejected", rejected)
                .toString(),
        )
    }

    /**
     * Download a LoRA into `root/loras`. An announced size at or above the
     * threshold needs an affirmative confirmation 结构化追问; a cancel (or a
     * CANCEL/timeout from AskGate) returns before any file or directory is
     * written. The destination never includes a URL query, so a signed URL's
     * token cannot leak into the tool result or the file name.
     */
    private fun downloadLora(
        command: ToolCommand.DownloadLora,
        ask: (String, List<String>) -> String,
    ): ToolResult {
        val root = ModelLibrary.resolve(command.dir, System.getenv()).toAbsolutePath().normalize()
        val name = loraFileName(command.url)
            ?: return ToolResult.Rejected("无法从下载地址确定 LoRA 文件名")
        val dest = root.resolve("loras").resolve(name).normalize()
        if (!dest.startsWith(root)) return ToolResult.Rejected("下载目标越出模型根目录：$name")

        val announced = try {
            downloader.contentLength(command.url)
        } catch (e: IOException) {
            return ToolResult.Rejected("无法读取 LoRA 大小：${e.message}")
        }
        if (announced > 0 && announced >= largeFileBytes) {
            val megabytes = announced / (1024 * 1024)
            val answer = ask("LoRA 文件约 $megabytes MB，确认下载？", listOf(DOWNLOAD_OPTION, CANCEL_OPTION))
            if (answer != DOWNLOAD_OPTION) return ToolResult.Ok("已取消下载 LoRA：$name")
        }
        return try {
            val bytes = downloader.download(command.url, dest)
            ToolResult.Ok("已下载 LoRA：$dest（$bytes 字节）")
        } catch (e: IOException) {
            ToolResult.Rejected("下载 LoRA 失败：${e.message}")
        }
    }

    /** Keep only a safe file name from the URL path; add the LoRA extension. */
    private fun loraFileName(url: String): String? {
        val raw = runCatching { URI(url).path.substringAfterLast('/') }.getOrNull()?.trim()
        if (raw.isNullOrEmpty() || raw == "." || raw == "..") return null
        val safe = raw.map { c -> if (c.isLetterOrDigit() || c in "._-") c else '_' }.joinToString("")
        return if (safe.endsWith(".safetensors", ignoreCase = true)) safe else "$safe.safetensors"
    }

    /**
     * Delete a LoRA only after an affirmative confirmation 结构化追问. Both the
     * root containment check and [isLoraFile] are reused from the read-only
     * inventory, so delete can never reach a checkpoint or a path outside the
     * library. A cancel, an AskGate CANCEL and a timeout all skip the delete.
     */
    private fun deleteLora(command: ToolCommand.DeleteLora, ask: (String, List<String>) -> String): ToolResult {
        val root = ModelLibrary.resolve(command.dir, System.getenv()).toAbsolutePath().normalize()
        val target = runCatching { Path.of(command.path.trim()).toAbsolutePath().normalize() }.getOrNull()
            ?: return ToolResult.Rejected("无效的 LoRA 路径：${command.path}")
        if (!target.startsWith(root) || !isLoraFile(target, root)) {
            return ToolResult.Rejected("路径不在 LoRA 目录内：${command.path}")
        }
        if (!Files.isRegularFile(target)) {
            return ToolResult.Rejected("LoRA 文件不存在：${command.path}")
        }
        val answer = ask("删除 LoRA：${target.fileName}?", listOf(DELETE_OPTION, CANCEL_OPTION))
        if (answer != DELETE_OPTION) return ToolResult.Ok("已取消删除 LoRA：${target.fileName}")
        return try {
            Files.delete(target)
            ToolResult.Ok("已删除 LoRA：$target")
        } catch (e: IOException) {
            ToolResult.Rejected("删除 LoRA 失败：${e.message}")
        }
    }

    /**
     * A LoRA here is a `.safetensors` below (but not at) the scan root, under a
     * directory named with "lora". Excluding the root itself keeps a library
     * folder such as `.../my-loras` from turning every checkpoint into a LoRA.
     */
    private fun isLoraFile(path: Path, root: Path): Boolean {
        if (!path.fileName.toString().endsWith(".safetensors", ignoreCase = true)) return false
        var parent = path.parent
        while (parent != null && parent != root) {
            if (parent.fileName?.toString()?.contains("lora", ignoreCase = true) == true) return true
            parent = parent.parent
        }
        return false
    }

    companion object {
        /** Mobile's ASK_ABOVE_MB threshold, in bytes (512 MB). */
        const val DEFAULT_LARGE_LORA_BYTES = 512L * 1024 * 1024

        const val SAVE_OVERWRITE_QUESTION = "覆盖已保存的工作流?"
        const val SAVE_OPTION = "保存"
        const val DELETE_OPTION = "删除"
        const val DOWNLOAD_OPTION = "下载"
        const val CANCEL_OPTION = "取消"
    }
}
