package vn.thaianh.doctruyen

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Trình đọc truyện trong app: mở web truyện (mặc định Tencent ac.qq.com),
 * lướt tới đâu dịch tới đó. Không cần quyền chia sẻ màn hình.
 */
class ReaderActivity : ComponentActivity() {

    companion object {
        const val HOME = "https://m.ac.qq.com/"
        private const val PREF = "reader"
    }

    private lateinit var web: WebView
    private lateinit var boxes: BoxesView
    private lateinit var status: TextView
    private lateinit var btnScan: ImageView
    private lateinit var btnAuto: ImageView

    private val handler = Handler(Looper.getMainLooper())
    private var auto = true
    private var busy = false
    private var pending = false
    private var scanGen = 0
    private var lastCopyResult = 0
    private var scans = 0
    private val autoScan = Runnable { if (auto) lifecycleScope.launch { scan(false) } }
    private val hideStatus = Runnable { status.visibility = View.GONE }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun circle(color: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(Color.parseColor(color)); setStroke(dp(2), Color.WHITE)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        // thanh trên: về, trang chủ, tải lại
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FFF6EA"))
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        fun barBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#2B2118"))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(44))
            setOnClickListener { onClick() }
        }
        bar.addView(barBtn("←") { if (web.canGoBack()) web.goBack() else finish() })
        bar.addView(barBtn("🏠") { web.loadUrl(HOME) })
        bar.addView(barBtn("⟳") { web.reload() })
        bar.addView(TextView(this).apply {
            text = "Đọc & dịch truyện"; textSize = 16f; setTextColor(Color.parseColor("#8A7462"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(8), 0, 0, 0)
        })

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val s = req.url.scheme ?: return true
                // chỉ mở web trong app; bỏ qua link bắt mở app Tencent / chợ ứng dụng
                return !(s == "http" || s == "https")
            }
            override fun onPageFinished(view: WebView, url: String) {
                getSharedPreferences(PREF, MODE_PRIVATE).edit().putString("last", url).apply()
                schedule(1200)
            }
        }
        web.setOnScrollChangeListener { _, _, _, _, _ -> onUserMoved() }
        web.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> onUserMoved()
                MotionEvent.ACTION_UP -> schedule(700)
            }
            false
        }

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // lớp ô dịch (không nhận chạm)
        boxes = BoxesView(this).apply { isClickable = false; isFocusable = false }
        root.addView(boxes, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // dòng thông báo
        status = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.parseColor("#D9333333")) }
            visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(64) })

        // nút nổi: husky = dịch, dấu chân = tự động
        val fab = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        btnAuto = ImageView(this).apply {
            setImageResource(R.drawable.paw); setPadding(dp(9), dp(9), dp(9), dp(9))
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { bottomMargin = dp(10) }
            setOnClickListener { setAuto(!auto) }
        }
        btnScan = ImageView(this).apply {
            setImageResource(R.drawable.husky_btn); background = circle("#FFB74D")
            setPadding(dp(5), dp(5), dp(5), dp(5)); elevation = dp(6).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(62), dp(62))
            setOnClickListener { lifecycleScope.launch { scan(true) } }
        }
        fab.addView(btnAuto); fab.addView(btnScan)
        root.addView(fab, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(14); bottomMargin = dp(28) })

        // chừa chỗ thanh trạng thái / thanh điều hướng (Android 15)
        root.setOnApplyWindowInsetsListener { v, insets ->
            var top: Int
            var bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(android.view.WindowInsets.Type.systemBars()); top = i.top; bottom = i.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            content.setPadding(0, top, 0, bottom)
            (fab.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(28) + bottom
            (status.layoutParams as FrameLayout.LayoutParams).topMargin = dp(64) + top
            fab.requestLayout(); status.requestLayout()
            insets
        }

        setContentView(root)
        setAuto(true, quiet = true)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })

        val last = getSharedPreferences(PREF, MODE_PRIVATE).getString("last", null)
        web.loadUrl(last ?: HOME)
        say("🐶 Lướt truyện bình thường, dừng tay là husky dịch liền", 3500)

        lifecycleScope.launch {
            if (Prefs.geminiKey(this@ReaderActivity).isEmpty() && !Translate.isModelReady()) {
                say("⏳ Đang tải bộ dịch lần đầu, anh chờ chút...", 6000)
                Translate.ensureModel()
                schedule(300)
            }
        }
    }

    private fun setAuto(on: Boolean, quiet: Boolean = false) {
        auto = on
        btnAuto.background = circle(if (on) "#22C55E" else "#9CA3AF")
        if (!quiet) say(if (on) "🐾 Tự động: lướt tới đâu dịch tới đó" else "Tắt tự động. Bấm husky để dịch", 2500)
        if (on) schedule(200) else handler.removeCallbacks(autoScan)
    }

    private fun onUserMoved() {
        scanGen++              // kết quả đang dịch dở (nếu có) sẽ bị bỏ
        boxes.clear()
        schedule(650)
    }

    private fun schedule(ms: Long) {
        handler.removeCallbacks(autoScan)
        if (auto) handler.postDelayed(autoScan, ms)
    }

    private fun say(msg: String, ms: Long = 2500) {
        status.text = msg; status.visibility = View.VISIBLE
        handler.removeCallbacks(hideStatus); handler.postDelayed(hideStatus, ms)
    }

    /** Chụp đúng vùng web đang hiển thị (ảnh trong app mình, không cần quyền chia sẻ màn hình). */
    private suspend fun captureWeb(): Bitmap? {
        if (web.width == 0 || web.height == 0) return null
        val loc = IntArray(2); web.getLocationInWindow(loc)
        val bmp = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
        val viaCopy: Bitmap? = suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(window, Rect(loc[0], loc[1], loc[0] + web.width, loc[1] + web.height), bmp,
                    { r -> lastCopyResult = r; cont.resume(if (r == PixelCopy.SUCCESS) bmp else null) }, handler)
            } catch (e: Exception) { lastCopyResult = -1; cont.resume(null) }
        }
        if (viaCopy != null) return viaCopy
        // cách dự phòng: vẽ web ra ảnh
        return try {
            val b2 = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
            web.draw(android.graphics.Canvas(b2)); b2
        } catch (e: Exception) { null }
    }

    private var spin: android.animation.ObjectAnimator? = null
    private fun setBusy(b: Boolean) {
        if (b) {
            spin = android.animation.ObjectAnimator.ofFloat(btnScan, "rotation", -14f, 14f).apply {
                duration = 250; repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE; start()
            }
        } else { spin?.cancel(); spin = null; btnScan.rotation = 0f }
    }

    private suspend fun scan(manual: Boolean) {
        if (busy) { pending = true; if (manual) say("⏳ Husky đang dịch, chờ xíu nha"); return }
        if (Prefs.geminiKey(this).isEmpty() && !Translate.isModelReady()) {
            if (manual) say("⏳ Bộ dịch chưa tải xong, anh chờ chút nha")
            return
        }
        busy = true; setBusy(true)
        val gen = ++scanGen
        try {
            val hadBoxes = boxes.hasBoxes
            boxes.clear()
            if (hadBoxes) delay(80)           // chờ ô dịch cũ biến mất khỏi hình
            val bmp = captureWeb() ?: run { if (manual) say("Chưa chụp được trang, anh thử lại nha"); return }
            val webLoc = IntArray(2); web.getLocationOnScreen(webLoc)
            val raw = Ocr.recognize(bmp)
            val groups = Ocr.group(raw, 0)
            scans++
            if (groups.isEmpty()) {
                // lần đầu hoặc bấm tay: báo chi tiết để biết hỏng ở đâu
                if (manual || scans <= 2) say("🔍 Chưa thấy chữ Trung (ảnh ${bmp.width}×${bmp.height}, sáng ${brightness(bmp)}%, nhận ${raw.textBlocks.size} mảnh chữ, copy=$lastCopyResult)", 5000)
                return
            }
            if (manual) say("🔍 Thấy ${groups.size} khung chữ, đang dịch...", 6000)
            val vi = Translate.translatePage(this, groups.map { it.second })
            if (gen != scanGen) return        // anh đã lướt đi chỗ khác trong lúc dịch
            val list = ArrayList<BoxesView.Box>()
            for ((i, g) in groups.withIndex()) {
                val tr = vi.getOrNull(i)?.trim().orEmpty()
                if (tr.isEmpty()) continue
                val r = Rect(g.first); r.offset(webLoc[0], webLoc[1])   // đổi sang toạ độ màn hình
                list.add(BoxesView.Box(r, tr))
            }
            boxes.show(list)
            if (manual || (Prefs.geminiKey(this).isNotEmpty() && !Translate.lastUsedAi)) {
                say(when {
                    list.isEmpty() -> "⚠️ Thấy chữ nhưng dịch chưa ra, anh thử lại nha"
                    Translate.lastUsedAi -> "✅ Dịch xong ${list.size} khung (AI)"
                    Prefs.geminiKey(this).isNotEmpty() -> "AI đang bận, tạm dùng bộ dịch trong máy"
                    else -> "✅ Dịch xong ${list.size} khung"
                })
            }
        } catch (e: Exception) {
            say("Lỗi dịch: ${e.javaClass.simpleName}: ${e.message}", 6000)
        } finally {
            busy = false
            setBusy(false)
            if (pending) { pending = false; schedule(300) }
        }
    }

    private fun brightness(bmp: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bmp, 16, 32, true)
        val px = IntArray(16 * 32); small.getPixels(px, 0, 16, 0, 0, 16, 32)
        var sum = 0L
        for (c in px) sum += ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
        return (sum * 100 / (px.size * 255L)).toInt()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }
}
