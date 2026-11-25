// Copied (excerpt) from app/src/main/java/com/abrah/nightmare/api/FlowRequest.kt on 2026-11-25 (ADR-0001). Edit the desktop copy; never sync back.
package com.abrah.nightmare.api

import org.json.JSONObject

/** A refusal with its HTTP status — said to the caller as `{"error": message}`. */
class ApiError(val code: Int, message: String) : Exception(message)

/**
 * ⭐⭐ What `POST /run` asks for (`docs/AGENT-API.md` §3). Exactly one of [flow] (a recipe id,
 * `saved:<name>`, or `current` — the canvas autosave) and [graph] (a workflow JSON as the app
 * saves it).
 *
 * ⚠ Only the request body is copied here. Its consumers — `applyOverrides`, `frameShaped`,
 * `apiRange` and `checked` — are typed in `Graph`/`NodeType`/`Widget` and stay with the mobile
 * executor until that registry is separated (see `desktop/docs/copied-files.md`).
 */
data class RunRequest(
    val flow: String? = null,
    val graph: String? = null,
    val model: String? = null,
    val prompt: String? = null,
    val negative: String? = null,
    val knobs: Map<String, String> = emptyMap(),
    val params: Map<String, Map<String, String>> = emptyMap(),
    val images: Map<String, String> = emptyMap(),
    val videos: Map<String, String> = emptyMap(),
    /** Server-sent events while it runs; otherwise one JSON answer at the end. */
    val stream: Boolean = false,
) {
    companion object {
        /** ⭐ The knobs [applyOverrides] sends to every sampler that has them. */
        val KNOBS = listOf("seed", "subseed", "subseed_strength", "steps", "cfg", "denoise", "scheduler", "width", "height", "aspect", "loras")

        fun parse(text: String): RunRequest {
            val o = try {
                JSONObject(text.ifBlank { "{}" })
            } catch (e: Exception) {
                throw ApiError(400, "the body is not JSON: ${e.message}")
            }
            fun str(k: String): String? = if (o.has(k) && !o.isNull(k)) o.get(k).toString() else null
            val flow = str("flow")
            val graph = o.optJSONObject("graph")?.toString()
            if ((flow == null) == (graph == null)) throw ApiError(400, "give exactly one of \"flow\" and \"graph\"")
            val unknown = o.keys().asSequence().toSet() -
                (KNOBS + listOf("flow", "graph", "model", "prompt", "negative", "params", "images", "videos", "stream"))
            if (unknown.isNotEmpty()) throw ApiError(400, "unknown field(s): ${unknown.sorted().joinToString()}")
            fun obj(k: String): Map<String, String> {
                val v = o.optJSONObject(k) ?: return emptyMap()
                return v.keys().asSequence().associateWith { v.get(it).toString() }
            }
            val params = o.optJSONObject("params")?.let { p ->
                p.keys().asSequence().associateWith { id ->
                    val inner = p.optJSONObject(id) ?: throw ApiError(400, "params.$id must be an object")
                    inner.keys().asSequence().associateWith { inner.get(it).toString() }
                }
            }.orEmpty()
            return RunRequest(
                flow = flow,
                graph = graph,
                model = str("model"),
                prompt = str("prompt"),
                negative = str("negative"),
                knobs = KNOBS.mapNotNull { k -> str(k)?.let { k to it } }.toMap(),
                params = params,
                images = obj("images"),
                videos = obj("videos"),
                stream = o.optBoolean("stream", false),
            )
        }
    }
}
