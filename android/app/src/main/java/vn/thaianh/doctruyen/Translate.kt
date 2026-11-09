package vn.thaianh.doctruyen

import android.content.Context
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Cài đặt nhỏ lưu trong máy. */
object Prefs {
    private const val FILE = "doc_truyen"
    fun geminiKey(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("gemini_key", "")!!.trim()
    fun setGeminiKey(ctx: Context, key: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("gemini_key", key.trim()).apply()
    /** Mẫu FreeModel đã chọn được lần trước (đỡ phải dò lại). */
    fun fmModel(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("fm_model2", "")!!
    fun setFmModel(ctx: Context, m: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("fm_model2", m).apply()
    /** Mã FreeModel bắt đầu bằng fm_ , còn lại coi là mã Gemini. */
    fun isFreeModel(key: String) = key.startsWith("fm_")
}

/**
 * Dịch Trung → Việt.
 * - Có mã Gemini + có mạng: dịch cả trang một lượt bằng AI, giữ ngữ cảnh, xưng hô kiểu truyện.
 * - Không có: dùng bộ dịch ML Kit chạy trong máy.
 */
object Translate {
    private val translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                .build()
        )
    }
    private val cache = LruCache<String, String>(800)

    /** Lần dịch gần nhất có dùng AI không (để báo cho anh biết). */
    @Volatile var lastUsedAi = false
    @Volatile var lastAiError: String? = null
    @Volatile private var aiCooldownUntil = 0L

    suspend fun isModelReady(): Boolean = try {
        val mgr = RemoteModelManager.getInstance()
        val zh = TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build()
        val vi = TranslateRemoteModel.Builder(TranslateLanguage.VIETNAMESE).build()
        mgr.isModelDownloaded(zh).await() && mgr.isModelDownloaded(vi).await()
    } catch (e: Exception) { false }

    suspend fun ensureModel(): Boolean = try {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        true
    } catch (e: Exception) { false }

    private suspend fun offline(text: String): String {
        cache.get("o:$text")?.let { return it }
        val out = try { translator.translate(text).await() } catch (e: Exception) { "" }
        if (out.isNotBlank()) cache.put("o:$text", out)
        return out
    }

    /** Dịch tất cả khung thoại của 1 trang, trả về đúng thứ tự. */
    suspend fun translatePage(ctx: Context, texts: List<String>): List<String> {
        if (texts.isEmpty()) return emptyList()
        val key = Prefs.geminiKey(ctx)
        lastUsedAi = false
        val cooling = System.currentTimeMillis() < aiCooldownUntil
        if (key.isNotEmpty() && !cooling) {
            val pageKey = "g:" + texts.joinToString("\u0001")
            cache.get(pageKey)?.let { lastUsedAi = true; return it.split("\u0001") }
            val ai = try {
                kotlinx.coroutines.withTimeoutOrNull(if (Prefs.isFreeModel(key)) 25000 else 15000) {
                    if (Prefs.isFreeModel(key)) freeModel(ctx, key, texts) else gemini(key, texts)
                }
                    ?: run { lastAiError = "AI trả lời quá chậm"; null }
            } catch (e: Exception) { lastAiError = e.message; null }
            // AI bận: nghỉ 1 phút rồi mới thử lại, trong lúc đó dịch bằng máy cho nhanh
            if (ai == null) aiCooldownUntil = System.currentTimeMillis() + 60_000
            if (ai != null && ai.size == texts.size) {
                lastUsedAi = true; lastAiError = null
                lastPageVi = ai
                cache.put(pageKey, ai.joinToString("\u0001"))
                return ai
            }
        }
        return texts.map { offline(it) }
    }

    private const val PROMPT =
        "Bạn là dịch giả manhua (truyện tranh Trung) sang tiếng Việt lâu năm, văn phong như các nhóm dịch truyện tranh Việt Nam.\n" +
        "Đầu vào là chữ máy quét (OCR) từ các khung thoại trên CÙNG MỘT TRANG, theo thứ tự đọc. OCR có thể nhận sai/thiếu vài chữ, " +
        "dính chữ của khung bên cạnh: hãy đoán ý đúng theo ngữ cảnh, đừng dịch máy móc theo từng chữ sai.\n" +
        "Cách dịch:\n" +
        "- Dịch Ý, không dịch từng chữ. Câu tiếng Việt phải đọc tự nhiên như người Việt nói, ngắn gọn vừa khung thoại.\n" +
        "- Đoán thể loại và quan hệ nhân vật từ nội dung (và trang trước nếu có) rồi chọn xưng hô cho đúng, GIỮ NHẤT QUÁN: " +
        "tu tiên/cổ trang dùng ta/ngươi, ta/con, sư phụ, tiền bối, tại hạ, bổn tọa...; hiện đại dùng tôi/anh/em/cậu/tớ/mày/tao...\n" +
        "- Tên riêng, cảnh giới, công pháp, môn phái để âm Hán Việt (Trúc Cơ, Kim Đan, Thanh Vân Tông).\n" +
        "- Thành ngữ, câu chửi, câu cà khịa: dùng cách nói tương đương của tiếng Việt cho có hồn.\n" +
        "- Tiếng động (轰, 砰, 哈哈): dịch thật ngắn (Ầm!, Bốp!, Haha). Chữ rác vô nghĩa: trả \"\".\n" +
        "CHỈ trả về một mảng JSON các chuỗi, đúng số phần tử và đúng thứ tự đầu vào. Không đánh số, không giải thích.\n"

    /** Trang vừa dịch xong, gửi kèm để AI giữ xưng hô và mạch truyện. */
    @Volatile private var lastPageVi: List<String> = emptyList()

    private fun pageInput(texts: List<String>): String {
        val prev = lastPageVi.filter { it.isNotBlank() }.takeLast(8)
        val ctx = if (prev.isEmpty()) "" else "Trang trước (đã dịch, chỉ để tham khảo ngữ cảnh): " + JSONArray(prev) + "\n"
        return ctx + "Trang này:\n" + JSONArray(texts)
    }

    /** Lỗi do máy chủ AI bận (không phải do mã). */
    class AiBusy(msg: String) : RuntimeException(msg)
    class AiBadKey(msg: String) : RuntimeException(msg)

    private val MODELS = listOf(
        "gemini-flash-latest", "gemini-2.5-flash", "gemini-flash-lite-latest", "gemini-2.5-flash-lite", "gemini-2.0-flash"
    )

    private suspend fun gemini(key: String, texts: List<String>): List<String>? = withContext(Dispatchers.IO) {
        val input = pageInput(texts)
        var lastBusy: String? = null
        for (model in MODELS) {
            var thinkingOff = true
            var attempt = 0
            while (attempt < 3) {
                attempt++
                val gen = JSONObject()
                    .put("temperature", 0.3)
                    .put("responseMimeType", "application/json")
                if (thinkingOff) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", PROMPT + input)))))
                    .put("generationConfig", gen)
                val con = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000; readTimeout = 25000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("x-goog-api-key", key)
                }
                val (code, resp) = try {
                    con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val c = con.responseCode
                    val r = (if (c in 200..299) con.inputStream else con.errorStream)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    Pair(c, r)
                } finally { con.disconnect() }

                when {
                    code in 200..299 -> {
                        val text = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                        return@withContext parseArray(text, texts.size)
                    }
                    code == 400 && resp.contains("API_KEY_INVALID") -> throw AiBadKey("mã không đúng")
                    code == 400 && thinkingOff -> { thinkingOff = false; attempt-- }   // mẫu không hỗ trợ tắt suy nghĩ
                    code == 401 || code == 403 -> throw AiBadKey("mã không có quyền ($code)")
                    code == 404 -> break                                                 // mẫu không còn, thử mẫu khác
                    code == 429 || code >= 500 -> {                                     // AI bận: chờ chút rồi thử lại / đổi mẫu
                        lastBusy = "AI đang bận ($code)"
                        if (attempt < 2) delay(800L * attempt) else break
                    }
                    else -> break
                }
            }
        }
        if (lastBusy != null) throw AiBusy(lastBusy)
        null
    }

    // ───────────── FreeModel (kiểu OpenAI) ─────────────
    private const val FM_BASE = "https://freemodel.in/v1"
    /** Đã thử dịch truyện: qwen (rành tiếng Trung), gpt-5.5, kimi dịch mượt và trả đúng dạng, ~4-5 giây/trang.
     *  Mẫu flash nhanh hơn nhưng hay trả sai dạng nên để dự phòng. */
    private val FM_FAST = listOf("qwen3.7-max", "gpt-5.5", "kimi-k3", "deepseek-v4.1-flash", "gemini-3.6-flash")

    private fun http(method: String, url: String, key: String, body: String?): Pair<Int, String> {
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000; readTimeout = 12000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (body != null) doOutput = true
        }
        return try {
            if (body != null) con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val c = con.responseCode
            val r = (if (c in 200..299) con.inputStream else con.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            Pair(c, r)
        } finally { con.disconnect() }
    }

    /** Mẫu chạy được lần trước lên đầu, rồi tới các mẫu nhanh còn lại. */
    private fun fmCandidates(ctx: Context): List<String> {
        val saved = Prefs.fmModel(ctx)
        return if (saved in FM_FAST) listOf(saved) + (FM_FAST - saved) else FM_FAST
    }

    /** Lấy bản dịch trong câu trả lời: mảng JSON, hoặc danh sách "1. ..." nếu AI lỡ trả kiểu đó. */
    private fun parseArray(text: String, n: Int): List<String>? {
        val t = text.trim()
        val a = t.indexOf('['); val b = t.lastIndexOf(']')
        try {
            val arr = JSONArray(if (a >= 0 && b > a) t.substring(a, b + 1) else t)
            val l = List(arr.length()) { arr.optString(it, "") }
            if (l.size == n) return l
        } catch (_: Exception) { }
        val num = Regex("^\\s*\\d+\\s*[.):、]\\s*(.*)$")
        val lines = t.lines().mapNotNull { num.find(it)?.groupValues?.get(1)?.trim()?.trim('"', '“', '”', '「', '」') }
        return if (lines.size == n) lines else null
    }

    private suspend fun freeModel(ctx: Context, key: String, texts: List<String>): List<String>? = withContext(Dispatchers.IO) {
        val input = pageInput(texts)
        var lastBusy: String? = null
        for (model in fmCandidates(ctx)) {
            var attempt = 0
            while (attempt < 2) {
                attempt++
                val body = JSONObject()
                    .put("model", model)
                    .put("temperature", 0.4)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", PROMPT))
                        .put(JSONObject().put("role", "user").put("content", input)))
                val (code, resp) = http("POST", "$FM_BASE/chat/completions", key, body.toString())
                when {
                    code in 200..299 -> {
                        val text = JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").optString("content", "")
                        val list = parseArray(text, texts.size)
                        if (list != null) {
                            if (Prefs.fmModel(ctx) != model) Prefs.setFmModel(ctx, model)
                            return@withContext list
                        }
                        break                                                    // trả lời lộn xộn: thử mẫu khác
                    }
                    code == 401 -> throw AiBadKey("mã không đúng ($code)")
                    code == 402 -> throw AiBusy("hết token miễn phí (402)")
                    code == 429 || code >= 500 -> {
                        lastBusy = "FreeModel đang bận ($code)"
                        if (attempt < 2) delay(1000) else break
                    }
                    else -> {                                                    // 400/403/404: mẫu không dùng được
                        if (Prefs.fmModel(ctx) == model) Prefs.setFmModel(ctx, "")
                        lastBusy = "mẫu $model không dùng được ($code)"
                        break
                    }
                }
            }
        }
        if (lastBusy != null) throw AiBusy(lastBusy)
        null
    }

    /** Thử mã Gemini anh vừa dán. */
    /** 1 = dùng được, 0 = mã sai, -1 = mã đúng nhưng AI đang bận/mất mạng. */
    suspend fun testKey(ctx: Context, key: String): Int = try {
        val sample = listOf("师父，我已经突破筑基了！")
        val r = if (Prefs.isFreeModel(key)) { Prefs.setFmModel(ctx, ""); freeModel(ctx, key, sample) } else gemini(key, sample)
        if (r?.firstOrNull()?.isNotBlank() == true) 1 else -1
    } catch (e: AiBadKey) { lastAiError = e.message; 0 }
    catch (e: Exception) { lastAiError = e.message; -1 }
}
