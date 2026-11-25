// Copied (excerpt) from app/src/main/java/com/abrah/nightmare/api/A1111.kt on 2026-11-25 (ADR-0001). Edit the desktop copy; never sync back.
package com.abrah.nightmare.api

/**
 * ⭐⭐ The Automatic1111 path helpers that are free of the Android model catalog: the A1111
 * prefix test and the aspect a client's `width`/`height` maps onto.
 *
 * ⚠ What stayed behind, and why: `route`, `txt2img`, `knobsFor`, `samplerFor`, `sdModels`,
 * `options`, `setOptions` and `modelNamed` all speak in `ModelSpec`/`Family`/`SelectedModel`
 * (`ModelCatalog.kt`) and call `ApiCore` (`Graph`/`NodeType`, `Bitmap`). Moving those is not a
 * copy — see `desktop/docs/copied-files.md`. The engine's own A1111 service is ticket 17.
 */
class A1111 {
    companion object {
        /** ⚠ One request cannot hold the phone for an hour: batch_size x n_iter is capped. */
        const val MAX_IMAGES = 4

        val TXT2IMG = listOf("sdapi", "v1", "txt2img")

        /** ⭐ True when [parts] is an A1111 path this class answers; the caller already checked the token. */
        fun handles(parts: List<String>): Boolean = parts.size >= 2 && parts[0] == "sdapi" && parts[1] == "v1"

        // --- copied from ModelCatalog.kt's ASPECTS section (the pure part) -----------------

        /**
         * ⚠ `1:1` is the identity and is deliberately first: the backend ignores an
         * `aspect_ratio` whose terms are equal, so sending it is the same as
         * sending nothing. ⭐ Squarest first.
         */
        val ASPECTS = listOf("1:1", "9:7", "7:9", "4:3", "3:4", "3:2", "2:3", "5:3", "3:5", "16:9", "9:16")

        const val DEFAULT_ASPECT = "1:1"

        /** ⚠ Pure: `w:h` as a number, or null when it is not one. */
        fun ratioValue(ratio: String): Float? {
            val (a, b) = ratio.split(':').takeIf { it.size == 2 } ?: return null
            val w = a.trim().toFloatOrNull() ?: return null
            val h = b.trim().toFloatOrNull() ?: return null
            return if (w > 0f && h > 0f) w / h else null
        }

        /** ⚠ Pure: the [ASPECTS] entry nearest [w]x[h] by log ratio. */
        fun nearestAspect(w: Int, h: Int): String {
            val want = kotlin.math.ln(w.toDouble() / h)
            return ASPECTS.minBy { a ->
                kotlin.math.abs(kotlin.math.ln(ratioValue(a)!!.toDouble()) - want)
            }
        }
    }
}
