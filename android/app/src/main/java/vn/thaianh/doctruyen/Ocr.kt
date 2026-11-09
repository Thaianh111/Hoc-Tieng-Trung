package vn.thaianh.doctruyen

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** Nhận chữ Trung trong ảnh và gộp theo khung thoại (dùng chung cho dịch màn hình và trình đọc). */
object Ocr {
    val CJK = Regex("[\\u3400-\\u9FFF\\uF900-\\uFAFF]")

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun read(bmp: Bitmap, topCut: Int = 0): List<Pair<Rect, String>> =
        group(recognizer.process(InputImage.fromBitmap(bmp, 0)).await(), topCut)

    suspend fun recognize(bmp: Bitmap): Text = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()

    /** Gộp các mảnh chữ cùng một khung thoại thành một ô, sắp theo thứ tự đọc. */
    fun group(result: Text, topCut: Int): List<Pair<Rect, String>> {
        data class Piece(val r: Rect, val text: String, val lineH: Int)
        val pieces = ArrayList<Piece>()
        for (block in result.textBlocks) {
            val r = block.boundingBox ?: continue
            if (r.bottom < topCut) continue
            val zh = block.lines.joinToString("") { it.text }.replace(Regex("\\s+"), "")
            if (!CJK.containsMatchIn(zh)) continue
            val lh = block.lines.mapNotNull { it.boundingBox?.height() }.average().let { if (it.isNaN()) r.height() else it.toInt() }
            pieces.add(Piece(Rect(r), zh, lh.coerceAtLeast(8)))
        }
        if (pieces.isEmpty()) return emptyList()
        // hợp nhất: chồng nhau, hoặc nằm sát trên/dưới và cùng cột
        val parent = IntArray(pieces.size) { it }
        fun find(x: Int): Int { var a = x; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
        for (i in pieces.indices) for (j in i + 1 until pieces.size) {
            val a = pieces[i].r; val b = pieces[j].r
            val lh = maxOf(pieces[i].lineH, pieces[j].lineH)
            val ov = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val narrow = minOf(a.width(), b.width()).coerceAtLeast(1)
            val vGap = maxOf(a.top, b.top) - minOf(a.bottom, b.bottom)
            val hGap = maxOf(a.left, b.left) - minOf(a.right, b.right)
            val oh = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            val sameCol = ov > narrow * 0.3f && vGap < lh * 0.9f
            val sameRow = oh > minOf(a.height(), b.height()) * 0.5f && hGap < lh * 0.6f
            if (Rect.intersects(a, b) || sameCol || sameRow) parent[find(i)] = find(j)
        }
        val map = LinkedHashMap<Int, MutableList<Piece>>()
        for (i in pieces.indices) map.getOrPut(find(i)) { ArrayList() }.add(pieces[i])
        return map.values.map { ps ->
            val sorted = ps.sortedWith(compareBy({ it.r.top / maxOf(1, it.lineH) }, { it.r.left }))
            val u = Rect(sorted[0].r); for (p in sorted) u.union(p.r)
            u to sorted.joinToString("") { it.text }
        }.sortedWith(compareBy({ it.first.top }, { it.first.left }))
    }

}
