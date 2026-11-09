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
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("fm_model", "")!!
    fun setFmModel(ctx: Context, m: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("fm_model", m).apply()
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
                cache.put(pageKey, ai.joinToString("\u0001"))
                return ai
            }
        }
        return texts.map { offline(it) }
    }

    private const val PROMPT =
        "Bạn là dịch giả truyện tranh Trung Quốc (manhua) sang tiếng Việt cho độc giả Việt Nam.\n" +
        "Dưới đây là các khung thoại trên CÙNG MỘT TRANG truyện, theo thứ tự đọc. Hãy dùng ngữ cảnh chung để dịch.\n" +
        "Yêu cầu:\n" +
        "- Dịch tự nhiên, trôi chảy như truyện tranh tiếng Việt, kiểu văn convert quen thuộc.\n" +
        "- Truyện tu tiên, cổ trang: xưng hô ta/ngươi, tại hạ, các hạ, sư phụ, sư huynh, sư tỷ, tiền bối, bổn tọa...\n" +
        "- Truyện đô thị, hiện đại: xưng hô tôi/anh/em/cô/cậu/mày/tao theo đúng quan hệ nhân vật.\n" +
        "- Tên người, tên môn phái, chiêu thức, cảnh giới giữ âm Hán Việt (ví dụ: Trúc Cơ, Kim Đan, Thiên Kiếm Tông).\n" +
        "- Câu ngắn gọn để vừa khung thoại, giữ cảm xúc (!, ?, ...).\n" +
        "- Tiếng động, ký hiệu: dịch rất ngắn hoặc giữ nguyên. Chữ nhận sai, vô nghĩa: trả về chuỗi rỗng.\n" +
        "Chỉ trả về một mảng JSON các chuỗi, ĐÚNG số phần tử và ĐÚNG thứ tự như đầu vào.\n\nĐầu vào:\n"

    /** Lỗi do máy chủ AI bận (không phải do mã). */
    class AiBusy(msg: String) : RuntimeException(msg)
    class AiBadKey(msg: String) : RuntimeException(msg)

    private val MODELS = listOf(
        "gemini-flash-latest", "gemini-2.5-flash", "gemini-flash-lite-latest", "gemini-2.5-flash-lite", "gemini-2.0-flash"
    )

    private suspend fun gemini(key: String, texts: List<String>): List<String>? = withContext(Dispatchers.IO) {
        val input = JSONArray(texts).toString()
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
                        val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                        val arr = JSONArray(clean)
                        return@withContext List(arr.length()) { arr.optString(it, "") }
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
    /** Ưu tiên mẫu nhỏ, nhanh, rẻ token; trong danh sách có gì thì lấy nấy. */
    private val FM_PREFER = listOf("mini", "flash", "lite", "haiku", "small", "fast", "turbo")
    private val FM_FALLBACK = listOf("gpt-5.4-mini", "gpt-4o-mini", "gpt-5-mini")

    private fun http(method: String, url: String, key: String, body: String?): Pair<Int, String> {
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000; readTimeout = 25000
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

    /** Hỏi FreeModel có những mẫu nào, xếp mẫu nhỏ/nhanh lên đầu. */
    private fun fmCandidates(ctx: Context, key: String): List<String> {
        val out = mutableListOf<String>()
        Prefs.fmModel(ctx).takeIf { it.isNotEmpty() }?.let { out += it }
        try {
            val (code, resp) = http("GET", "$FM_BASE/models", key, null)
            if (code == 401 || code == 403) throw AiBadKey("mã không đúng ($code)")
            if (code in 200..299) {
                val arr = JSONObject(resp).optJSONArray("data") ?: JSONArray()
                val ids = List(arr.length()) { arr.getJSONObject(it).optString("id") }
                    .filter { it.isNotBlank() && !it.contains("image", true) && !it.contains("embed", true) }
                val ranked = ids.sortedBy { id ->
                    FM_PREFER.indexOfFirst { id.contains(it, true) }.let { if (it < 0) 99 else it }
                }
                out += ranked
            }
        } catch (e: AiBadKey) { throw e } catch (_: Exception) { }
        out += FM_FALLBACK
        return out.distinct().take(6)
    }

    /** Lấy mảng JSON trong câu trả lời (AI hay thêm chữ hoặc ``` bao quanh). */
    private fun parseArray(text: String): List<String> {
        val t = text.trim()
        val a = t.indexOf('['); val b = t.lastIndexOf(']')
        val arr = JSONArray(if (a >= 0 && b > a) t.substring(a, b + 1) else t)
        return List(arr.length()) { arr.optString(it, "") }
    }

    private suspend fun freeModel(ctx: Context, key: String, texts: List<String>): List<String>? = withContext(Dispatchers.IO) {
        val input = JSONArray(texts).toString()
        var lastBusy: String? = null
        for (model in fmCandidates(ctx, key)) {
            var attempt = 0
            while (attempt < 2) {
                attempt++
                val body = JSONObject()
                    .put("model", model)
                    .put("temperature", 0.3)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", PROMPT + input)))
                val (code, resp) = http("POST", "$FM_BASE/chat/completions", key, body.toString())
                when {
                    code in 200..299 -> {
                        val text = JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").optString("content", "")
                        val list = try { parseArray(text) } catch (e: Exception) { null }
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
