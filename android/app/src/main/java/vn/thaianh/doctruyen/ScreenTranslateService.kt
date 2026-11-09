package vn.thaianh.doctruyen

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs

class ScreenTranslateService : Service() {

    companion object {
        @Volatile var running = false
        const val ACTION_START = "vn.thaianh.doctruyen.START"
        const val ACTION_STOP = "vn.thaianh.doctruyen.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "dich_man_hinh"
        private const val NOTI_ID = 7

        private const val GRID_W = 32
        private const val GRID_H = 64
        private const val CHANGE = 6      // màn hình đổi (đang lướt)
        private const val STABLE = 3      // màn hình đứng yên
        private val CJK = Regex("[\\u3400-\\u9FFF\\uF900-\\uFAFF]")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val capThread = HandlerThread("chup-man-hinh").apply { start() }
    private val capHandler = Handler(capThread.looper)

    @Volatile private var latest: Bitmap? = null
    @Volatile private var latestTime = 0L
    @Volatile private var lastGrab = 0L
    @Volatile private var grabPosted = false

    private var screenW = 0
    private var screenH = 0

    private var boxesView: BoxesView? = null
    private var controls: LinearLayout? = null
    private var btnScan: ImageView? = null
    private var btnAuto: ImageView? = null
    private var pulse: android.animation.ObjectAnimator? = null

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private var auto = false
    private var busy = false
    private var modelReady = false
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startAsForeground()
                if (projection != null) return START_NOT_STICKY
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                if (data == null) {
                    stopSelf(); return START_NOT_STICKY
                }
                wm = getSystemService(WindowManager::class.java)
                try {
                    setupCapture(code, data)
                } catch (e: Exception) {
                    toast("Không chụp được màn hình: ${e.message}")
                    stopSelf(); return START_NOT_STICKY
                }
                setupOverlay()
                startWatchLoop()
                running = true
                scope.launch {
                    modelReady = Translate.isModelReady()
                    if (!modelReady && Prefs.geminiKey(this@ScreenTranslateService).isEmpty()) {
                        toast("Đang tải bộ dịch Trung → Việt, anh chờ chút...")
                        modelReady = Translate.ensureModel()
                        toast(if (modelReady) "Bộ dịch sẵn sàng rồi!" else "Chưa tải được bộ dịch, anh kiểm tra mạng nha")
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    // ---------------- thông báo chạy nền ----------------
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Dịch màn hình", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, ScreenTranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val openPi = PendingIntent.getActivity(this, 2,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.paw)
            .setContentTitle("Đang dịch màn hình")
            .setContentText("Bấm \"Tắt\" để dừng dịch")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "Tắt", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    // ---------------- chụp màn hình ----------------
    private fun setupCapture(code: Int, data: Intent) {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels; screenH = dm.heightPixels
        }
        val dpi = resources.displayMetrics.densityDpi
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("không có quyền")
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { scope.launch { stopSelf() } }
        }, Handler(Looper.getMainLooper()))
        projection = mp

        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({
            // lấy khung hình mới nhất, tối đa ~5 lần/giây cho đỡ tốn pin
            if (!grabPosted) {
                grabPosted = true
                val wait = (lastGrab + 200 - SystemClock.uptimeMillis()).coerceAtLeast(0)
                capHandler.postDelayed({ grabNow() }, wait)
            }
        }, capHandler)
        reader = r
        virtualDisplay = mp.createVirtualDisplay("dich-truyen", screenW, screenH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
    }

    private fun grabNow() {
        grabPosted = false
        val img = try { reader?.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            lastGrab = SystemClock.uptimeMillis()
            latest = toBitmap(img)
            latestTime = lastGrab
        } catch (_: Exception) {
        } finally {
            img.close()
        }
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val buf = plane.buffer
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * img.width
        val bmp = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        return if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
    }

    // ---------------- lớp nổi trên màn hình ----------------
    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlay() {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        // lớp vẽ ô dịch: không nhận chạm để anh vẫn lướt truyện bình thường
        val bv = BoxesView(this)
        val bp = WindowManager.LayoutParams(screenW, screenH, type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            // Android 12+: lớp phủ phải hơi trong suốt thì chạm mới xuyên qua được
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(bv, bp)
        boxesView = bv

        // nút nổi: mặt husky = dịch, dấu chân = tự động, ✕ = tắt
        fun round(color: String, sizeDp: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Color.parseColor(color)); setStroke(dp(2), Color.WHITE)
        }.let { bgd -> Pair(bgd, sizeDp) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val scan = ImageView(this).apply {
            setImageResource(R.drawable.husky_btn)
            background = round("#FFB74D", 62).first
            setPadding(dp(5), dp(5), dp(5), dp(5))
            elevation = dp(6).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(62), dp(62)).apply { bottomMargin = dp(8) }
        }
        val autoBtn = ImageView(this).apply {
            setImageResource(R.drawable.paw)
            background = round("#B39CA3AF", 46).first
            setPadding(dp(9), dp(9), dp(9), dp(9))
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { bottomMargin = dp(8) }
        }
        val close = TextView(this).apply {
            text = "✕"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = round("#99374151", 30).first
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
        }
        col.addView(scan); col.addView(autoBtn); col.addView(close)

        val cp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - dp(74); y = screenH / 3
        }

        // kéo nút 🔍 để dời cả cụm; chạm nhẹ = dịch
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        scan.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = cp.x; startY = cp.y; dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        cp.x = (startX + dx).toInt().coerceIn(0, screenW - dp(60))
                        cp.y = (startY + dy).toInt().coerceIn(0, screenH - dp(180))
                        try { wm.updateViewLayout(col, cp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) scope.launch { scan(manual = true) }
                    true
                }
                else -> true
            }
        }
        autoBtn.setOnClickListener { setAuto(!auto) }
        close.setOnClickListener { stopSelf() }

        wm.addView(col, cp)
        controls = col; btnScan = scan; btnAuto = autoBtn

        val st = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9)); maxWidth = (screenW * 0.85f).toInt()
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.parseColor("#E6333333")) }
            visibility = View.GONE
        }
        val sp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(70); alpha = 0.8f
        }
        wm.addView(st, sp)
        statusView = st
        toast("🐶 Đã bật! Mở truyện rồi bấm mặt husky để dịch", 3500)
    }

    private fun setAuto(on: Boolean) {
        auto = on
        (btnAuto?.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (on) "#F022C55E" else "#B39CA3AF"))
        toast(if (on) "🐾 Tự động: lướt tới đâu dịch tới đó" else "Tắt tự động. Bấm mặt husky để dịch")
        if (on) scope.launch { scan(manual = false) }
    }

    private fun setBusyUi(b: Boolean) {
        val v = btnScan ?: return
        if (b) {
            pulse = android.animation.ObjectAnimator.ofFloat(v, "rotation", -12f, 12f).apply {
                duration = 260; repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE; start()
            }
        } else {
            pulse?.cancel(); pulse = null; v.rotation = 0f
        }
    }

    // ---------------- nhận chữ + dịch ----------------
    private suspend fun scan(manual: Boolean) {
        if (busy) return
        if (!modelReady && Prefs.geminiKey(this).isEmpty()) {
            modelReady = Translate.isModelReady()
            if (!modelReady) {
                if (manual) toast("Bộ dịch chưa tải xong, anh chờ chút nha")
                return
            }
        }
        busy = true
        setBusyUi(true)
        try {
            // ẩn ô dịch cũ rồi chờ khung hình sạch
            val hadBoxes = boxesView?.hasBoxes == true
            boxesView?.clear()
            val t = SystemClock.uptimeMillis()
            if (hadBoxes) {
                var waited = 0
                while (latestTime < t + 60 && waited < 700) { delay(50); waited += 50 }
            }
            val bmp = latest
            if (bmp == null) {
                if (manual) toast("Chưa chụp được màn hình, anh thử lại nha")
                return
            }
            if (withContext(Dispatchers.Default) { isBlack(bmp) }) {
                if (manual) toast("😢 Ảnh chụp ra màu đen: app này chặn chụp màn hình. Anh thử đọc trên web ac.qq.com bằng Chrome nha", 6000)
                return
            }
            val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
            val groups = groupBlocks(result)
            if (manual) toast(if (groups.isEmpty()) "🔍 Không thấy chữ Trung trên màn hình" else "🔍 Thấy ${groups.size} khung chữ, đang dịch...", 2500)
            val vi = Translate.translatePage(this, groups.map { it.second })
            val list = ArrayList<BoxesView.Box>()
            for ((i, g) in groups.withIndex()) {
                val tr = vi.getOrNull(i)?.trim().orEmpty()
                if (tr.isNotEmpty()) list.add(BoxesView.Box(g.first, tr))
            }
            // trong lúc dịch mà anh đã lướt sang chỗ khác thì bỏ kết quả cũ
            val now = latest
            if (!manual && now != null && now !== bmp) {
                val moved = withContext(Dispatchers.Default) { diff(signature(now), signature(bmp)) > CHANGE }
                if (moved) { baseline = null; shownAt = SystemClock.uptimeMillis(); return }
            }
            if (manual && Prefs.geminiKey(this).isNotEmpty() && !Translate.lastUsedAi && groups.isNotEmpty())
                toast("AI chưa dịch được (${Translate.lastAiError ?: "mất mạng?"}), dùng bộ dịch trong máy", 3500)
            boxesView?.show(list)
            if (manual && groups.isNotEmpty()) toast(
                if (list.isEmpty()) "⚠️ Thấy chữ nhưng dịch chưa ra. AI bận và bộ dịch trong máy chưa tải xong?"
                else "✅ Dịch xong ${list.size} khung" + if (Translate.lastUsedAi) " (AI)" else " (trong máy)", 2500)
            shownAt = SystemClock.uptimeMillis()
            baseline = null
        } catch (e: Exception) {
            if (manual) toast("Lỗi dịch: ${e.message}")
        } finally {
            busy = false
            setBusyUi(false)
        }
    }

    /** Gộp các mảnh chữ cùng một khung thoại thành một ô, sắp theo thứ tự đọc. */
    private fun groupBlocks(result: com.google.mlkit.vision.text.Text): List<Pair<Rect, String>> {
        data class Piece(val r: Rect, val text: String, val lineH: Int)
        val topCut = (screenH * 0.035f).toInt()   // bỏ thanh trạng thái
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

    // ---------------- theo dõi lướt trang ----------------
    private var baseline: IntArray? = null
    private var shownAt = 0L

    private fun signature(bmp: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(bmp, GRID_W, GRID_H, true)
        val px = IntArray(GRID_W * GRID_H)
        small.getPixels(px, 0, GRID_W, 0, 0, GRID_W, GRID_H)
        if (small !== bmp) small.recycle()
        // bỏ 2 hàng trên cùng (thanh trạng thái có đồng hồ)
        for (i in 0 until GRID_W * 2) px[i] = 0
        for (i in px.indices) {
            if (i < GRID_W * 2) continue
            val c = px[i]
            px[i] = ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10
        }
        return px
    }

    private fun diff(a: IntArray, b: IntArray): Int {
        var s = 0L
        for (i in a.indices) s += abs(a[i] - b[i])
        return (s / a.size).toInt()
    }

    private fun startWatchLoop() {
        loopJob = scope.launch {
            var moving = false
            var prev: IntArray? = null
            var stableCount = 0
            while (isActive) {
                delay(250)
                if (busy) continue
                val bmp = latest ?: continue
                val frameTime = latestTime
                val sig = withContext(Dispatchers.Default) { signature(bmp) }
                if (!moving) {
                    val base = baseline
                    if (base == null) {
                        // chờ khung hình có ô dịch mới vẽ rồi mới lấy mốc
                        if (frameTime > shownAt + 150 || SystemClock.uptimeMillis() - shownAt > 900) baseline = sig
                        continue
                    }
                    if (diff(sig, base) > CHANGE) {
                        // anh đang lướt: ẩn ô dịch cũ
                        boxesView?.clear()
                        moving = true; prev = sig; stableCount = 0
                    }
                } else {
                    val p = prev
                    stableCount = if (p != null && diff(sig, p) <= STABLE) stableCount + 1 else 0
                    prev = sig
                    if (stableCount >= 2) {
                        moving = false
                        if (auto) scan(manual = false) else { baseline = null; shownAt = SystemClock.uptimeMillis() }
                    }
                }
            }
        }
    }

    private var statusView: TextView? = null
    private val hideStatus = Runnable { statusView?.visibility = View.GONE }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Hiện dòng thông báo nhỏ trên màn hình (tự làm, vì máy hay chặn Toast của app chạy nền). */
    private fun toast(msg: String, ms: Long = 3000) {
        mainHandler.post {
            val v = statusView
            if (v == null) {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show(); return@post
            }
            v.text = msg; v.visibility = View.VISIBLE
            mainHandler.removeCallbacks(hideStatus)
            mainHandler.postDelayed(hideStatus, ms)
        }
    }

    private fun isBlack(bmp: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(bmp, 24, 48, true)
        val px = IntArray(24 * 48); small.getPixels(px, 0, 24, 0, 0, 24, 48)
        if (small !== bmp) small.recycle()
        var bright = 0
        for (c in px) if (((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) > 60) bright++
        return bright < px.size * 0.03   // gần như toàn bộ màu đen
    }

    override fun onDestroy() {
        running = false
        loopJob?.cancel()
        scope.cancel()
        try { boxesView?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { controls?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { statusView?.let { wm.removeView(it) } } catch (_: Exception) {}
        mainHandler.removeCallbacksAndMessages(null)
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { recognizer.close() } catch (_: Exception) {}
        capThread.quitSafely()
        projection = null
        super.onDestroy()
    }
}
