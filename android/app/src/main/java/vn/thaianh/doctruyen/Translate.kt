package vn.thaianh.doctruyen

import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/** Dịch Trung → Việt chạy ngay trong máy (ML Kit). */
object Translate {
    private val translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                .build()
        )
    }
    private val cache = LruCache<String, String>(500)

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

    suspend fun translate(text: String): String {
        cache.get(text)?.let { return it }
        val out = try { translator.translate(text).await() } catch (e: Exception) { "" }
        if (out.isNotBlank()) cache.put(text, out)
        return out
    }
}
