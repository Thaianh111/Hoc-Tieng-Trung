package vn.thaianh.doctruyen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View

/** Lớp trong suốt phủ toàn màn hình, vẽ ô dịch tiếng Việt đè lên khung thoại. Các ô không đè lên nhau. */
@SuppressLint("ViewConstructor")
class BoxesView(context: Context) : View(context) {

    data class Box(val rect: Rect, val text: String)
    private class Placed(val box: RectF, val layout: StaticLayout)

    private var boxes: List<Box> = emptyList()
    private var placed: List<Placed>? = null
    private val loc = IntArray(2)

    private fun px(v: Float, unit: Int = TypedValue.COMPLEX_UNIT_DIP) =
        TypedValue.applyDimension(unit, v, resources.displayMetrics)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = px(1.5f); color = Color.parseColor("#FFB74D")
    }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111111"); typeface = Typeface.DEFAULT_BOLD
    }

    private val minText = px(11f, TypedValue.COMPLEX_UNIT_SP)
    private val maxText = px(20f, TypedValue.COMPLEX_UNIT_SP)
    private val step = px(1f, TypedValue.COMPLEX_UNIT_SP)
    private val pad = px(4f)
    private val gap = px(3f)
    private val minW = px(96f)
    private val radius = px(9f)

    val hasBoxes: Boolean get() = boxes.isNotEmpty()

    fun show(list: List<Box>) {
        boxes = list; placed = null; invalidate()
    }

    fun clear() {
        if (boxes.isNotEmpty()) { boxes = emptyList(); placed = null; invalidate() }
    }

    private fun build(text: String, size: Float, w: Int): StaticLayout {
        tp.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, tp, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(false)
            .build()
    }

    private fun hOverlap(a: RectF, b: RectF) = a.left < b.right && b.left < a.right

    /** Xếp chỗ cho các ô: vừa khung, không tràn đè ô khác. */
    private fun layoutAll(): List<Placed> {
        getLocationOnScreen(loc)
        val sw = width.toFloat()
        val sh = height.toFloat()
        val src = boxes.sortedBy { it.rect.top }.map { b ->
            RectF(b.rect.left - loc[0].toFloat(), b.rect.top - loc[1].toFloat(),
                b.rect.right - loc[0].toFloat(), b.rect.bottom - loc[1].toFloat()) to b.text
        }
        val out = ArrayList<Placed>()
        for ((i, pair) in src.withIndex()) {
            val (r, text) = pair
            // bề rộng: theo khung chữ gốc, nới thêm chút nếu khung hẹp
            var w = maxOf(r.width() + pad * 2, minW, r.width() * 1.25f)
            w = minOf(w, sw - pad * 2)
            var left = r.centerX() - w / 2f
            left = left.coerceIn(pad, sw - pad - w)
            var top = r.top - pad
            val probe = RectF(left, top, left + w, top + 1)

            // không chen lên ô đã đặt phía trên
            for (p in out) if (hOverlap(p.box, probe) && top < p.box.bottom + gap && p.box.top <= top + 1) {
                top = p.box.bottom + gap
            }
            // giới hạn đáy: trước khung chữ kế tiếp bên dưới (cùng cột)
            var limit = sh - pad
            for (j in i + 1 until src.size) {
                val n = src[j].first
                if (n.top > top && hOverlap(n, RectF(left, 0f, left + w, 1f))) { limit = minOf(limit, n.top - gap); }
            }
            val wantH = maxOf(r.height() + pad * 2, limit - top).coerceAtLeast(px(20f))
            val inner = (w - pad * 2).toInt().coerceAtLeast(10)
            val fitH = minOf(r.height() + pad * 2 + px(24f), wantH) - pad * 2

            var size = maxText
            var lay = build(text, size, inner)
            while (lay.height > fitH && size > minText) {
                size -= step; lay = build(text, size, inner)
            }
            // vẫn chưa vừa: thử nới ngang thêm
            if (lay.height > fitH && w < sw - pad * 2) {
                val w2 = minOf(sw - pad * 2, w * 1.5f)
                left = (r.centerX() - w2 / 2f).coerceIn(pad, sw - pad - w2)
                w = w2
                lay = build(text, size, (w - pad * 2).toInt())
            }
            val h = maxOf(r.height().toFloat(), lay.height.toFloat()) + pad * 2
            out.add(Placed(RectF(left, top, left + w, top + h), lay))
        }
        return out
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (boxes.isEmpty()) return
        val list = placed ?: layoutAll().also { placed = it }
        for (p in list) {
            canvas.drawRoundRect(p.box, radius, radius, bg)
            canvas.drawRoundRect(p.box, radius, radius, border)
            canvas.save()
            val ty = p.box.top + (p.box.height() - p.layout.height) / 2f
            canvas.translate(p.box.left + pad, ty)
            p.layout.draw(canvas)
            canvas.restore()
        }
    }
}
