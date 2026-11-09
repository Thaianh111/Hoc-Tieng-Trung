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

/** Lớp trong suốt phủ toàn màn hình, vẽ ô dịch tiếng Việt đè lên chỗ có chữ Trung. */
@SuppressLint("ViewConstructor")
class BoxesView(context: Context) : View(context) {

    data class Box(val rect: Rect, val text: String)

    private var boxes: List<Box> = emptyList()
    private val loc = IntArray(2)

    private fun px(v: Float, unit: Int = TypedValue.COMPLEX_UNIT_DIP) =
        TypedValue.applyDimension(unit, v, resources.displayMetrics)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = px(1.5f); color = Color.parseColor("#F59E0B")
    }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111111"); typeface = Typeface.DEFAULT_BOLD
    }

    private val minText = px(14f, TypedValue.COMPLEX_UNIT_SP)
    private val maxText = px(22f, TypedValue.COMPLEX_UNIT_SP)
    private val pad = px(5f)
    private val minW = px(110f)
    private val radius = px(10f)

    val hasBoxes: Boolean get() = boxes.isNotEmpty()

    fun show(list: List<Box>) {
        boxes = list
        invalidate()
    }

    fun clear() {
        if (boxes.isNotEmpty()) {
            boxes = emptyList()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (boxes.isEmpty()) return
        getLocationOnScreen(loc)
        val screenW = width.toFloat()
        for (b in boxes) {
            val r = b.rect
            // khung theo vị trí chữ gốc, nới ra chút cho dễ đọc
            var w = maxOf(r.width() + pad * 2, minW)
            if (w > screenW - pad * 2) w = screenW - pad * 2
            val cx = r.exactCenterX() - loc[0]
            var left = cx - w / 2f
            if (left < pad) left = pad
            if (left + w > screenW - pad) left = screenW - pad - w
            val top = r.top - loc[1] - pad
            val innerW = (w - pad * 2).toInt().coerceAtLeast(10)
            val innerH = r.height().toFloat()

            // chọn cỡ chữ vừa khung (lớn nhất có thể, không nhỏ hơn minText)
            var size = maxText
            var layout: StaticLayout
            while (true) {
                tp.textSize = size
                layout = StaticLayout.Builder.obtain(b.text, 0, b.text.length, tp, innerW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(0f, 1.05f)
                    .setIncludePad(false)
                    .build()
                if (layout.height <= innerH || size <= minText) break
                size -= px(1f, TypedValue.COMPLEX_UNIT_SP)
            }
            val h = maxOf(innerH, layout.height.toFloat()) + pad * 2
            val box = RectF(left, top, left + w, top + h)
            canvas.drawRoundRect(box, radius, radius, bg)
            canvas.drawRoundRect(box, radius, radius, border)
            canvas.save()
            val ty = top + pad + (h - pad * 2 - layout.height) / 2f
            canvas.translate(left + pad, ty)
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
