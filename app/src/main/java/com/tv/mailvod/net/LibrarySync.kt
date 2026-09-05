package com.tv.mailvod.net

import android.util.Log
import com.tv.mailvod.store.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gitee 片库同步: 从配置的片库地址(默认 Gitee 仓库 raw 直链)拉取投递格式 JSON,
 * 解析成条目后交由 LibraryStore.merge 幂等合并(title+episode 唯一键)。
 * 取代旧邮件(IMAP)通道 - 无登录/无凭据/无 MIME 解析, 仅一次 HTTPS GET。
 */
class LibrarySync {

    companion object {
        private const val TAG = "LibrarySync"

        /** 连接与读取超时(毫秒), 避免刷新后长时间无响应。 */
        private const val TIMEOUT_MS = 15_000
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 拉取片库地址并解析条目; 网络/解析失败抛异常, 由调用方提示。阻塞 IO, 须在协程调用。 */
    suspend fun fetch(url: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val text = httpGet(url)
        parse(text)
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "mailvod-android")
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 解析投递格式: JSON 数组或单对象; 兼容 JSON 前后有杂文(正则回退)。title/url 必填非空白。 */
    internal fun parse(text: String): List<VideoItem> {
        val candidate = text.trim()
        if (candidate.isEmpty()) return emptyList()
        val raw = when {
            (candidate.startsWith("[") && candidate.endsWith("]")) ||
                (candidate.startsWith("{") && candidate.endsWith("}")) -> candidate
            else -> Regex("\\[.*]").find(candidate)?.value
                ?: Regex("\\{.*}").find(candidate)?.value
        } ?: return emptyList()
        val element = runCatching { json.parseToJsonElement(raw) }.getOrElse {
            Log.w(TAG, "json parse failed: ${it.message}")
            return emptyList()
        }
        val objs = when (element) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(element)
            else -> return emptyList()
        }
        return objs.mapIndexedNotNull { idx, obj ->
            parseItem(obj).also {
                if (it == null) Log.i(TAG, "obj[$idx] 跳过: title/url 为空")
            }
        }
    }

    private fun parseItem(obj: JsonObject): VideoItem? {
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()
        val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim()
        if (title.isNullOrBlank() || url.isNullOrBlank()) return null
        return VideoItem(
            id = 0,
            title = title,
            episode = obj["episode"]?.jsonPrimitive?.intOrNull ?: 0,
            year = obj["year"]?.jsonPrimitive?.intOrNull,
            country = obj["country"]?.jsonPrimitive?.contentOrNull,
            type = obj["type"]?.jsonPrimitive?.contentOrNull,
            director = obj["director"]?.jsonPrimitive?.contentOrNull,
            actors = obj["actors"]?.jsonPrimitive?.contentOrNull,
            url = url,
            headers = (obj["headers"] as? JsonObject)
                ?.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" }
                ?.filterValues { it.isNotBlank() }
                ?: emptyMap(),
            addedAt = 0
        )
    }
}
