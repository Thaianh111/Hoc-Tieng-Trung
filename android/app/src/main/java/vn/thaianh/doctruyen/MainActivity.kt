package vn.thaianh.doctruyen

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var tvOverlay: TextView
    private lateinit var tvModel: TextView
    private lateinit var btnStart: Button

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK && res.data != null) {
                val i = Intent(this, ScreenTranslateService::class.java).apply {
                    action = ScreenTranslateService.ACTION_START
                    putExtra(ScreenTranslateService.EXTRA_CODE, res.resultCode)
                    putExtra(ScreenTranslateService.EXTRA_DATA, res.data)
                }
                ContextCompat.startForegroundService(this, i)
                // về màn hình chính để anh mở Tencent
                moveTaskToBack(true)
            }
        }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(36))
            setBackgroundColor(Color.parseColor("#FFFAF2"))
        }
        fun title(t: String, size: Float = 26f) = TextView(this).apply {
            text = t; textSize = size; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#2B2118"))
            setPadding(0, dp(14), 0, dp(4))
        }
        fun body(t: String) = TextView(this).apply {
            text = t; textSize = 17f; setTextColor(Color.parseColor("#5A4A3A"))
            setLineSpacing(0f, 1.2f)
        }
        fun button(t: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
            text = t; textSize = 18f; isAllCaps = false
            setTextColor(if (primary) Color.WHITE else Color.parseColor("#2B2118"))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (primary) Color.parseColor("#B91C1C") else Color.WHITE)
                setStroke(dp(1), Color.parseColor("#EADFCE"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(if (primary) 72 else 58)).apply { topMargin = dp(10) }
            setOnClickListener { onClick() }
        }

        col.addView(title("📖 Dịch Truyện Trung", 28f))
        col.addView(body("Dịch chữ Trung ngay trên màn hình, đè lên khung thoại. Chạy được với Tencent và mọi app tiếng Trung."))

        col.addView(title("Bước 1 · Cho phép", 20f))
        tvOverlay = body("")
        col.addView(tvOverlay)
        col.addView(button("Cho phép hiện trên ứng dụng khác") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        if (Build.VERSION.SDK_INT >= 33) {
            col.addView(button("Cho phép thông báo") {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            })
        }

        col.addView(title("Bước 2 · Tải bộ dịch (1 lần)", 20f))
        tvModel = body("")
        col.addView(tvModel)
        col.addView(button("Tải bộ dịch Trung → Việt") { downloadModel() })

        col.addView(title("Bước 3 · Bật dịch", 20f))
        col.addView(body("Khi điện thoại hỏi chia sẻ màn hình, anh chọn \"Toàn bộ màn hình\" rồi bấm Bắt đầu."))
        btnStart = button("▶  BẬT DỊCH MÀN HÌNH", primary = true) { start() }
        col.addView(btnStart)
        col.addView(button("■  Tắt dịch") {
            startService(Intent(this, ScreenTranslateService::class.java).setAction(ScreenTranslateService.ACTION_STOP))
        })

        col.addView(title("Cách dùng", 20f))
        col.addView(body(
            "• Trên màn hình sẽ có 3 nút tròn nổi:\n" +
            "   🔍  bấm để dịch trang đang xem\n" +
            "   🔁  bật/tắt TỰ ĐỘNG: lướt tới đâu dịch tới đó (nút xanh là đang bật)\n" +
            "   ✕  tắt dịch\n" +
            "• Giữ và kéo nút 🔍 để dời chỗ.\n" +
            "• Đang lướt thì ô dịch tự ẩn, dừng tay khoảng nửa giây là hiện bản dịch mới.\n" +
            "• Máy RedMagic: nếu app hay bị tắt, vào Cài đặt → Pin → cho app này chạy nền không giới hạn."))

        col.addView(body("\n© Võ Thái Anh"))

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val ok = Settings.canDrawOverlays(this)
        tvOverlay.text = if (ok) "✅ Đã cho phép hiện trên ứng dụng khác" else "❌ Chưa cho phép hiện trên ứng dụng khác"
        lifecycleScope.launch {
            tvModel.text = if (Translate.isModelReady()) "✅ Bộ dịch đã sẵn sàng" else "❌ Chưa tải bộ dịch (khoảng 30–60MB, nên dùng Wi-Fi)"
        }
    }

    private fun downloadModel() {
        tvModel.text = "⏳ Đang tải bộ dịch, anh chờ chút..."
        lifecycleScope.launch {
            val ok = Translate.ensureModel()
            tvModel.text = if (ok) "✅ Bộ dịch đã sẵn sàng" else "❌ Tải chưa được. Anh kiểm tra mạng rồi bấm lại nha."
        }
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            tvOverlay.text = "❌ Anh bấm \"Cho phép hiện trên ứng dụng khác\" trước nha"
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }
}

