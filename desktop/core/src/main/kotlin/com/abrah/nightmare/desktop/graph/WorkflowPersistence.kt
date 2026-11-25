package com.abrah.nightmare.desktop.graph

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Success or a single readable failure; GUI code never has to catch a raw parser exception. */
sealed interface WorkflowResult<out T> {
    data class Ok<out T>(val value: T) : WorkflowResult<T>
    data class Err(val error: WorkflowError) : WorkflowResult<Nothing>
}

/** The one error type behind the GUI's 错误条. [message] is what the bar shows. */
data class WorkflowError(val kind: Kind, val message: String, val cause: Throwable? = null) {
    enum class Kind {
        INVALID_JSON,
        INCOMPATIBLE_VERSION,
        UNSAFE_NODE_ID,
        UNWRITABLE_PATH,
    }

    override fun toString(): String = message
}

/**
 * Minimal 工作流 JSON reader/writer for the desktop GUI. Deliberately self-written:
 * the Android `WorkflowIo` pulls in execution and video/NPU assets. The wire shape
 * is exactly the one [Workflow.toJson] emits, so writing and reading cannot drift.
 *
 * All entry points are pure with respect to the caller's workflow: a failed load
 * or save returns an error and never mutates the workflow being edited.
 */
object WorkflowPersistence {

    /** User story 5: start from an empty 工作流. Explicit; there is no auto-save. */
    fun newWorkflow(): Workflow = Workflow()

    /** Load from [path]; failure keeps whatever workflow the caller currently holds. */
    fun load(path: Path): WorkflowResult<Workflow> = try {
        parse(Files.readString(path, StandardCharsets.UTF_8))
    } catch (e: IOException) {
        failure(WorkflowError.Kind.UNWRITABLE_PATH, "无法读取工作流文件 $path（${e.message}）", e)
    }

    /**
     * Explicit save: write to a unique sibling temp file, then atomically replace
     * [path]. An unsafe node id or unwritable path fails without touching the
     * workflow or leaving a temp file behind.
     */
    fun save(workflow: Workflow, path: Path): WorkflowResult<Path> {
        workflow.nodes.firstOrNull { !isSafeNodeId(it.id) }?.let { bad ->
            return failure(WorkflowError.Kind.UNSAFE_NODE_ID, "节点 id 不安全：${bad.id}")
        }
        val duplicate = workflow.nodes.groupingBy { it.id }.eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicate != null) {
            return failure(WorkflowError.Kind.INVALID_JSON, "重复的节点 id：${duplicate.key}")
        }
        val parent = path.toAbsolutePath().parent
            ?: return failure(WorkflowError.Kind.UNWRITABLE_PATH, "路径不可写：$path（缺少父目录）")
        var temp: Path? = null
        return try {
            if (!Files.isDirectory(parent)) {
                return failure(WorkflowError.Kind.UNWRITABLE_PATH, "路径不可写：$parent（不是目录）")
            }
            temp = Files.createTempFile(parent, ".workflow-", ".tmp")
            Files.writeString(temp, workflow.toJson(), StandardCharsets.UTF_8)
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            WorkflowResult.Ok(path)
        } catch (e: IOException) {
            failure(WorkflowError.Kind.UNWRITABLE_PATH, "路径不可写：$path（${e.message}）", e)
        } finally {
            temp?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /** 另存为 shares the exact semantics of [save] with a different target path. */
    fun saveAs(workflow: Workflow, path: Path): WorkflowResult<Path> = save(workflow, path)

    private fun parse(text: String): WorkflowResult<Workflow> {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return failure(WorkflowError.Kind.INVALID_JSON, "工作流 JSON 无法解析：${e.message}", e)
        }

        val rawFormat = root.opt("format")
            ?: return failure(WorkflowError.Kind.INVALID_JSON, "工作流 JSON 缺少 format 字段")
        // Require the exact integer: a Long that wraps to 1 or a Double 1.5 must
        // not be mistaken for format 1 by a lossy toInt() conversion.
        if (rawFormat !is Int || rawFormat != WORKFLOW_FORMAT) {
            return failure(
                WorkflowError.Kind.INCOMPATIBLE_VERSION,
                "版本不兼容：format=$rawFormat，仅支持 $WORKFLOW_FORMAT",
            )
        }

        val array = root.opt("nodes") as? JSONArray
            ?: return failure(WorkflowError.Kind.INVALID_JSON, "工作流 JSON 缺少 nodes 数组")
        val nodes = ArrayList<Node>(array.length())
        val ids = HashSet<String>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
                ?: return failure(WorkflowError.Kind.INVALID_JSON, "节点 #$i 不是对象")
            val id = obj.optString("id", "")
            if (id.isEmpty()) {
                return failure(WorkflowError.Kind.INVALID_JSON, "节点 #$i 缺少 id")
            }
            if (!isSafeNodeId(id)) {
                return failure(WorkflowError.Kind.UNSAFE_NODE_ID, "节点 id 不安全：$id")
            }
            if (!ids.add(id)) {
                return failure(WorkflowError.Kind.INVALID_JSON, "重复的节点 id：$id")
            }
            val type = obj.optString("type", "")
            if (type.isEmpty()) {
                return failure(WorkflowError.Kind.INVALID_JSON, "节点 $id 缺少 type")
            }

            val params = LinkedHashMap<String, String>()
            obj.optJSONObject("params")?.let { source ->
                source.keys().forEach { key ->
                    val value = source.opt(key)
                    if (value !is String) {
                        return failure(
                            WorkflowError.Kind.INVALID_JSON,
                            "节点 $id 的参数 $key 不是字符串",
                        )
                    }
                    params[key] = value
                }
            }

            val inputs = LinkedHashMap<String, Source>()
            obj.optJSONObject("inputs")?.let { source ->
                source.keys().forEach { port ->
                    val value = source.opt(port)
                    if (value !is String || value.isEmpty()) {
                        return failure(
                            WorkflowError.Kind.INVALID_JSON,
                            "节点 $id 的输入 $port 不是有效的连线",
                        )
                    }
                    val parsed = Source.parse(value)
                    if (parsed.node.isEmpty() || !isSafeNodeId(parsed.node)) {
                        return failure(
                            WorkflowError.Kind.UNSAFE_NODE_ID,
                            "节点 $id 的输入 $port 指向不安全的节点：${parsed.node}",
                        )
                    }
                    inputs[port] = parsed
                }
            }

            val positionObj = obj.optJSONObject("position")
            val x = if (positionObj != null) positionObj.optDouble("x", 0.0) else obj.optDouble("x", 0.0)
            val y = if (positionObj != null) positionObj.optDouble("y", 0.0) else obj.optDouble("y", 0.0)
            nodes += Node(id, type, params, inputs, Position(x.toFloat(), y.toFloat()))
        }
        return WorkflowResult.Ok(Workflow(nodes))
    }

    private fun failure(kind: WorkflowError.Kind, message: String, cause: Throwable? = null) =
        WorkflowResult.Err(WorkflowError(kind, message, cause))
}
