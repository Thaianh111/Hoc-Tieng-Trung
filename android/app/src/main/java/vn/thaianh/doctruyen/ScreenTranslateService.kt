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
    private var btnScan: TextView? = null
    private var btnAuto: TextView? = null

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
                scope.launch {
                    modelReady = Translate.isModelReady()
                    if (!modelReady) {
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
            .setSmallIcon(R.drawable.ic_launcher_fg)
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

        // 3 nút tròn nổi
        fun circle(label: String, color: String) = TextView(this).apply {
            text = label; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.parseColor(color))
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply { bottomMargin = dp(8) }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val scan = circle("🔍", "#CCB91C1C")
        val autoBtn = circle("🔁", "#CC6B7280")
        val close = circle("✕", "#99374151").apply {
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(7) }
        }
        col.addView(scan); col.addView(autoBtn); col.addView(close)

        val cp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - dp(70); y = screenH / 3
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
    }

    private fun setAuto(on: Boolean) {
        auto = on
        (btnAuto?.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (on) "#E615803D" else "#CC6B7280"))
        toast(if (on) "TỰ ĐỘNG: lướt tới đâu dịch tới đó" else "Đã tắt tự động. Bấm 🔍 để dịch")
        if (on) scope.launch { scan(manual = false) }
    }

    private fun setBusyUi(b: Boolean) {
        btnScan?.text = if (b) "⏳" else "🔍"
    }

    // ---------------- nhận chữ + dịch ----------------
    private suspend fun scan(manual: Boolean) {
        if (busy) return
        if (!modelReady) {
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
            val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
            val list = ArrayList<BoxesView.Box>()
            for (block in result.textBlocks) {
                val rect = block.boundingBox ?: continue
                val zh = block.lines.joinToString("") { it.text }.replace(Regex("\\s+"), "")
                if (!CJK.containsMatchIn(zh)) continue
                val vi = Translate.translate(zh)
                if (vi.isBlank()) continue
                list.add(BoxesView.Box(Rect(rect), vi))
            }
            boxesView?.show(list)
            if (list.isEmpty() && manual) toast("Không thấy chữ Trung trên màn hình")
            shownAt = SystemClock.uptimeMillis()
            baseline = null
        } catch (e: Exception) {
            if (manual) toast("Lỗi dịch: ${e.message}")
        } finally {
            busy = false
            setBusyUi(false)
        }
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

    private fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        try { boxesView?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { controls?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { recognizer.close() } catch (_: Exception) {}
        capThread.quitSafely()
        projection = null
        super.onDestroy()
    }
}
