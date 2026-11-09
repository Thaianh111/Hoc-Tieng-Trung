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
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val ORANGE = "#FFB74D"
    private val ORANGE_DARK = "#F57C00"
    private val INK = "#2B2118"

    private lateinit var btnMain: Button
    private lateinit var chipOverlay: TextView
    private lateinit var chipModel: TextView
    private lateinit var chipAi: TextView
    private lateinit var keyInput: EditText
    private lateinit var keyStatus: TextView

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK && res.data != null) {
                val i = Intent(this, ScreenTranslateService::class.java).apply {
                    action = ScreenTranslateService.ACTION_START
                    putExtra(ScreenTranslateService.EXTRA_CODE, res.resultCode)
                    putExtra(ScreenTranslateService.EXTRA_DATA, res.data)
                }
                ContextCompat.startForegroundService(this, i)
                moveTaskToBack(true)   // về màn hình để anh mở Tencent
            }
        }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun roundBg(color: String, r: Int = 18, stroke: String? = null) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(Color.parseColor(color))
        if (stroke != null) setStroke(dp(1), Color.parseColor(stroke))
    }

    private fun text(t: String, size: Float, bold: Boolean = false, color: String = INK) = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun chip(): TextView = TextView(this).apply {
        textSize = 14f; gravity = Gravity.CENTER; setPadding(dp(10), dp(8), dp(10), dp(8))
        background = roundBg("#FFFFFF", 14, "#F1E4D3")
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(3); marginEnd = dp(3)
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = roundBg("#FFFFFF", 20, "#F1E4D3")
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(28), dp(18), dp(40))
            setBackgroundColor(Color.parseColor("#FFF6EA"))
        }

        // husky
        col.addView(ImageView(this).apply {
            setImageResource(R.drawable.husky_btn)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(ORANGE)) }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(128), dp(128))
            setOnClickListener { animate().rotationBy(360f).setDuration(500).start() }
        })
        col.addView(text("Dịch Truyện Trung", 26f, true).apply { setPadding(0, dp(10), 0, 0) })
        col.addView(text("Lướt tới đâu, dịch tới đó 🐾", 16f, color = "#8A7462"))

        // nút chính
        btnMain = Button(this).apply {
            textSize = 18f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(6) }
            setOnClickListener { onMainClick() }
        }
        // trình đọc trong app (dùng cho Tencent và web chặn chụp màn hình)
        col.addView(Button(this).apply {
            text = "📖  ĐỌC TRUYỆN TENCENT TRONG APP"; textSize = 18f; isAllCaps = false
            setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = roundBg("#2563EB", 22)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(70)).apply { topMargin = dp(16) }
            setOnClickListener { startActivity(Intent(this@MainActivity, ReaderActivity::class.java)) }
        })
        col.addView(text("Lướt tới đâu dịch tới đó, không cần chia sẻ màn hình", 14f, color = "#8A7462").apply { setPadding(0, dp(6), 0, 0) })
        col.addView(text("Nút cam bên dưới: dịch đè lên app khác (app không chặn chụp)", 14f, color = "#8A7462").apply { setPadding(0, dp(18), 0, 0) })
        col.addView(btnMain)

        // trạng thái
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        chipOverlay = chip(); chipModel = chip(); chipAi = chip()
        chips.addView(chipOverlay); chips.addView(chipModel); chips.addView(chipAi)
        col.addView(chips)

        // cách dùng
        col.addView(card().apply {
            addView(text("Cách dùng", 18f, true))
            addView(row(R.drawable.husky_btn, ORANGE, "Bấm mặt husky: dịch trang đang xem"))
            addView(row(R.drawable.paw, "#22C55E", "Bấm dấu chân: tự dịch khi lướt (xanh là đang bật)"))
            addView(text("✕   Tắt dịch.   Giữ & kéo husky để dời chỗ.", 15f, color = "#5A4A3A").apply { setPadding(dp(4), dp(10), 0, 0) })
        })

        // AI
        col.addView(card().apply {
            addView(text("✨ Dịch hay hơn bằng AI", 18f, true))
            addView(text("Miễn phí. Lấy mã ở trang Google AI Studio rồi dán vào đây.", 15f, color = "#5A4A3A").apply { setPadding(0, dp(4), 0, dp(8)) })
            addView(Button(this@MainActivity).apply {
                text = "🔑  Mở trang lấy mã"; isAllCaps = false; textSize = 16f
                setTextColor(Color.parseColor(INK)); background = roundBg("#FFF3E0", 14, "#FFD8A8")
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
                }
            })
            keyInput = EditText(this@MainActivity).apply {
                hint = "Dán mã vào đây (AIza...)"; textSize = 16f; setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                setText(Prefs.geminiKey(this@MainActivity))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            }
            addView(keyInput)
            addView(Button(this@MainActivity).apply {
                text = "Lưu & thử mã"; isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE)
                background = roundBg(ORANGE_DARK, 14)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) }
                setOnClickListener { saveKey() }
            })
            keyStatus = text("", 15f, color = "#5A4A3A").apply { setPadding(0, dp(6), 0, 0) }
            addView(keyStatus)
        })

        col.addView(text("© Võ Thái Anh", 13f, color = "#B4A08C").apply { setPadding(0, dp(18), 0, 0) })

        val root = ScrollView(this).apply { addView(col); setBackgroundColor(Color.parseColor("#FFF6EA")) }
        // Android 15 vẽ tràn lên thanh trạng thái: chừa chỗ cho khỏi bị che
        root.setOnApplyWindowInsetsListener { v, insets ->
            val top = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(android.view.WindowInsets.Type.systemBars()).top
            else @Suppress("DEPRECATION") insets.systemWindowInsetTop
            val bottom = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(android.view.WindowInsets.Type.systemBars()).bottom
            else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            v.setPadding(0, top, 0, bottom)
            insets
        }
        setContentView(root)
    }

    private fun row(icon: Int, color: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, 0)
        addView(ImageView(this@MainActivity).apply {
            setImageResource(icon)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(color)) }
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
        })
        addView(text(label, 15f, color = "#3B2F25").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val running = ScreenTranslateService.running
        btnMain.text = if (running) "■  TẮT DỊCH MÀN HÌNH" else "🐾  DỊCH ĐÈ MÀN HÌNH"
        btnMain.background = roundBg(if (running) "#6B7280" else ORANGE_DARK, 22)
        val ov = Settings.canDrawOverlays(this)
        chipOverlay.text = if (ov) "✅ Nổi" else "⚠️ Nổi"
        val hasKey = Prefs.geminiKey(this).isNotEmpty()
        chipAi.text = if (hasKey) "✅ AI" else "➖ AI"
        lifecycleScope.launch {
            chipModel.text = if (Translate.isModelReady()) "✅ Offline" else "⬇️ Offline"
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun onMainClick() {
        if (ScreenTranslateService.running) {
            startService(Intent(this, ScreenTranslateService::class.java).setAction(ScreenTranslateService.ACTION_STOP))
            lifecycleScope.launch { delay(300); refresh() }
            return
        }
        // bước 1: quyền hiện trên app khác
        if (!Settings.canDrawOverlays(this)) {
            toast("Bật công tắc cho \"Dịch Truyện Trung\" rồi bấm quay lại nha anh")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        // bước 2: thông báo (để có nút Tắt trên thanh thông báo)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // bước 3: bộ dịch trong máy (dự phòng khi không có mạng)
        lifecycleScope.launch {
            if (!Translate.isModelReady()) {
                if (Prefs.geminiKey(this@MainActivity).isEmpty()) {
                    btnMain.text = "⏳  Đang tải bộ dịch..."
                    btnMain.isEnabled = false
                    val ok = Translate.ensureModel()
                    btnMain.isEnabled = true
                    refresh()
                    if (!ok) { toast("Tải bộ dịch chưa được, anh kiểm tra mạng nha"); return@launch }
                } else {
                    // có AI rồi: tải bộ dịch dự phòng ngầm
                    launch { Translate.ensureModel(); refresh() }
                }
            }
            toast("Chọn \"Toàn bộ màn hình\" rồi bấm Bắt đầu nha anh")
            val mpm = getSystemService(MediaProjectionManager::class.java)
            captureLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    private fun saveKey() {
        val k = keyInput.text.toString().trim()
        Prefs.setGeminiKey(this, k)
        refresh()
        if (k.isEmpty()) { keyStatus.text = "Đã xoá mã. App sẽ dùng bộ dịch trong máy."; return }
        keyStatus.text = "⏳ Đang thử mã..."
        lifecycleScope.launch {
            keyStatus.text = when (Translate.testKey(k)) {
                1 -> "✅ Mã dùng được! Từ giờ app dịch bằng AI."
                0 -> "❌ Mã không đúng (${Translate.lastAiError}). Anh copy lại mã nha."
                else -> "🟡 Đã lưu mã. Máy chủ AI của Google đang bận (${Translate.lastAiError ?: "mạng chập chờn"}). App vẫn dịch bằng AI khi nó rảnh, lúc bận thì tự dùng bộ dịch trong máy."
            }
        }
    }

}
