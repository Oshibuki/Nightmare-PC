// Copied from app/src/main/java/com/abrah/nightmare/Framing.kt and
// app/src/main/java/com/abrah/nightmare/canvas/CropEditor.kt on 2026-11-26
// (ADR-0001/0007). Only the pure arithmetic used by CropRectTest was brought
// over; deriveSizes/rendersNowhere/sizeMismatches and the Compose editor stayed
// behind. Edit the desktop copy; never sync back.
package com.abrah.nightmare.desktop.canvas

import com.abrah.nightmare.desktop.graph.Node
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The crop frame in SOURCE PIXELS; it may lie partly outside the bitmap. */
data class Frame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun width() = right - left
    fun height() = bottom - top
}

/** Pure framing arithmetic — no executor, no size derivation. */
object CropGeometry {
    private const val MIN_PX = 1f

    fun frameOf(x: Float, y: Float, w: Float, h: Float, srcW: Int, srcH: Int): Frame {
        val fw = (w * srcW).coerceAtLeast(MIN_PX)
        val fh = (h * srcH).coerceAtLeast(MIN_PX)
        val fx = x * srcW
        val fy = y * srcH
        return Frame(fx, fy, fx + fw, fy + fh)
    }

    fun outputSize(outW: Int, outH: Int, frame: Frame, max: Int): Pair<Int, Int> =
        if (outW > 0 && outH > 0) {
            outW.coerceIn(1, max) to outH.coerceIn(1, max)
        } else {
            Math.round(frame.width()).coerceIn(1, max) to
                Math.round(frame.height()).coerceIn(1, max)
        }

    fun needsPadding(srcW: Int, srcH: Int, outW: Int, outH: Int): Boolean =
        outW > 0 && outH > 0 && (srcW < outW || srcH < outH)

    fun minScale(
        viewW: Float, viewH: Float, imgW: Float, imgH: Float, outW: Int,
        rule: PadRule = PadRule.WHEN_TOO_SMALL,
    ): Float {
        if (imgW <= 0f || imgH <= 0f) return 1f
        val cover = maxOf(viewW / imgW, viewH / imgH)
        return when (rule) {
            PadRule.NEVER -> cover
            PadRule.OUTPAINT, PadRule.PAD, PadRule.ZOOM ->
                minOf(viewW / imgW, viewH / imgH) / OUTPAINT_LIMIT
            PadRule.WHEN_TOO_SMALL -> if (outW <= 0) cover else minOf(cover, viewW / outW)
        }
    }

    /** √2 per edge is twice the photo's area. */
    val OUTPAINT_LIMIT = kotlin.math.sqrt(2f)

    fun photoPixels(photo: Frame, w: Int, h: Int): IntArray? {
        val l = ceil(photo.left * w - 1e-3f).toInt().coerceIn(0, w)
        val t = ceil(photo.top * h - 1e-3f).toInt().coerceIn(0, h)
        val r = floor(photo.right * w + 1e-3f).toInt().coerceIn(0, w)
        val b = floor(photo.bottom * h + 1e-3f).toInt().coerceIn(0, h)
        if (r - l < 1 || b - t < 1) return null
        return intArrayOf(l, t, r - l, b - t)
    }

    fun photoInFrame(x: Float, y: Float, w: Float, h: Float): Frame? {
        val eps = 2e-3f
        if (w <= 0f || h <= 0f) return null
        if (x >= -eps && y >= -eps && x + w <= 1f + eps && y + h <= 1f + eps) return null
        val l = ((0f - x) / w).coerceIn(0f, 1f)
        val t = ((0f - y) / h).coerceIn(0f, 1f)
        val r = ((1f - x) / w).coerceIn(0f, 1f)
        val b = ((1f - y) / h).coerceIn(0f, 1f)
        if (r <= l || b <= t) return Frame(0f, 0f, 0f, 0f)
        return Frame(l, t, r, b)
    }
}

/** When a frame may hang off the photo — the rule a sampler kind asks for. */
enum class PadRule {
    WHEN_TOO_SMALL,
    NEVER,
    OUTPAINT,
    PAD,
    ZOOM,
    ;

    val hidesPadChoice get() = this == NEVER || this == ZOOM
}

/** The crop rectangle, in fractions of the source image. May reach outside it. */
data class CropRect(val x: Float, val y: Float, val w: Float, val h: Float) {

    fun clamped(): CropRect {
        val cw = w.coerceIn(MIN, MAX_SPAN)
        val ch = h.coerceIn(MIN, MAX_SPAN)
        return CropRect(
            x.coerceIn(-cw + MIN, 1f - MIN),
            y.coerceIn(-ch + MIN, 1f - MIN),
            cw,
            ch,
        )
    }

    /** Within a rounding step of [o]? Params are stored to three decimals. */
    fun near(o: CropRect) =
        abs(x - o.x) < 2e-3f && abs(y - o.y) < 2e-3f &&
            abs(w - o.w) < 2e-3f && abs(h - o.h) < 2e-3f

    companion object {
        val WHOLE = CropRect(0f, 0f, 1f, 1f)
        const val MIN = 0.05f
        const val MAX_SPAN = 64f
        val FULL = CropRect(0f, 0f, 1f, 1f)
    }
}

/** Pan/zoom arithmetic as pure functions: `scale` maps picture to viewport px. */
object CropView {
    fun rectOf(
        viewW: Float, viewH: Float, imgW: Float, imgH: Float,
        scale: Float, offsetX: Float, offsetY: Float,
    ) = CropRect(
        x = -offsetX / (imgW * scale),
        y = -offsetY / (imgH * scale),
        w = viewW / (imgW * scale),
        h = viewH / (imgH * scale),
    ).clamped()

    fun scaleFor(viewW: Float, imgW: Float, rect: CropRect) =
        viewW / (rect.w.coerceAtLeast(CropRect.MIN) * imgW)

    fun clampOffset(
        viewW: Float, viewH: Float, imgW: Float, imgH: Float,
        scale: Float, offsetX: Float, offsetY: Float,
    ): Pt {
        val dx = viewW - imgW * scale
        val dy = viewH - imgH * scale
        return Pt(
            offsetX.coerceIn(min(0f, dx), max(0f, dx)),
            offsetY.coerceIn(min(0f, dy), max(0f, dy)),
        )
    }
}

/** Ignores a malformed param rather than throwing: the sheet must still open. */
fun cropRectOf(node: Node): CropRect = CropRect(
    node.params["x"]?.toFloatOrNull() ?: 0f,
    node.params["y"]?.toFloatOrNull() ?: 0f,
    node.params["w"]?.toFloatOrNull() ?: 1f,
    node.params["h"]?.toFloatOrNull() ?: 1f,
).clamped()

/** Whole photo in view, padded or covered per [rule]. */
fun wholePhotoFraming(srcW: Int, srcH: Int, aspect: Float, rule: PadRule): CropRect {
    if (srcW <= 0 || srcH <= 0 || aspect <= 0f) return CropRect.WHOLE
    val photo = srcW.toFloat() / srcH
    val wider = photo > aspect
    val fit = rule == PadRule.OUTPAINT || rule == PadRule.PAD
    val (w, h) = if (wider == fit) 1f to (photo / aspect) else (aspect / photo) to 1f
    return CropRect((1f - w) / 2f, (1f - h) / 2f, w, h)
}

/** Rounded, so the params a workflow stores stay short and stable. */
fun CropRect.asParams(): List<Pair<String, String>> = listOf(
    "x" to round3(x), "y" to round3(y), "w" to round3(w), "h" to round3(h),
)

private fun round3(v: Float) = (Math.round(v * 1000f) / 1000f).toString()
