package com.tv.mailvod.store

import android.content.Context
import com.tv.mailvod.download.MovieFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 读写本地 library.json（应用私有目录）。
 * - 条目无编号字段；唯一键 = title；列表按 _added_at 降序（新片在上，同刻按片名）。
 * - 合并：按 title 查找，命中覆盖 url/headers/元信息，保留原 _added_at；未命中追加（记当前时间）。
 * - 删除：按 title 移除条目。
 * - 兼容：旧版文件的 "_id" 字段被忽略；发现旧"编号.ts"产物时自动改名为"片名.ts"（一次性、幂等）。
 *
 * 所有 IO 在 IO 调度器；读返回已排序副本，写加锁。
 */
class LibraryStore(private val context: Context) {

    private val file = File(context.filesDir, "library.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val lock = Any()

    /** 旧版条目（带 _id），仅用于本地文件改名迁移。 */
    @Serializable
    private data class LegacyEntry(
        @SerialName("_id") val id: Long = 0,
        val title: String = ""
    )

    /** 读取并按入库时间降序返回。顺带做一次旧编号文件 → 片名文件的迁移。 */
    suspend fun load(): List<VideoItem> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!file.exists()) return@synchronized emptyList()
            val raw = file.readText()
            migrateLegacyFiles(raw)
            runCatching { json.decodeFromString<List<VideoItem>>(raw) }.getOrDefault(emptyList())
        }.sortedWith(compareByDescending<VideoItem> { it.addedAt }.thenBy { it.title })
    }

    /** 旧版"编号.ts"产物改名"片名.ts"（幂等：改名后 raw 仍含 _id 也能跳过）。 */
    private fun migrateLegacyFiles(raw: String) {
        if (!raw.contains("\"_id\"")) return
        runCatching {
            val legacy = json.decodeFromString<List<LegacyEntry>>(raw)
            val pairs = legacy.map { it.id to it.title }.filter { it.first > 0 && it.second.isNotBlank() }
            if (pairs.isNotEmpty()) MovieFiles.migrateLegacy(context, pairs)
        }
    }

    /**
     * 合并新解析的条目到 library.json，返回新增数量（未命中的；命中覆盖不计入）。
     */
    suspend fun merge(newItems: List<VideoItem>): Int = withContext(Dispatchers.IO) {
        if (newItems.isEmpty()) return@withContext 0
        synchronized(lock) {
            val current = if (file.exists()) {
                runCatching { json.decodeFromString<List<VideoItem>>(file.readText()) }.getOrDefault(emptyList())
            } else emptyList()

            val byKey = current.associateBy { it.key }.toMutableMap()
            var added = 0
            val now = System.currentTimeMillis() / 1000

            for (item in newItems) {
                val existing = byKey[item.key]
                if (existing == null) {
                    // 新片：记入库时间，追加
                    byKey[item.key] = item.copy(addedAt = now)
                    added++
                } else {
                    // 命中：覆盖 url/headers 与元信息，保留原入库时间
                    byKey[item.key] = existing.copy(
                        url = item.url,
                        headers = item.headers,
                        title = item.title,
                        year = item.year,
                        country = item.country,
                        type = item.type,
                        director = item.director,
                        actors = item.actors
                    )
                }
            }

            val merged = byKey.values.sortedWith(compareByDescending<VideoItem> { it.addedAt }.thenBy { it.title })
            file.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(VideoItem.serializer()), merged))
            added
        }
    }

    /** 按片名删除一条。 */
    suspend fun delete(title: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!file.exists()) return@withContext false
            val current = runCatching {
                json.decodeFromString<List<VideoItem>>(file.readText())
            }.getOrDefault(emptyList())
            val after = current.filterNot { it.title == title }
            val changed = after.size != current.size
            if (changed) {
                file.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(VideoItem.serializer()), after))
            }
            changed
        }
    }
}
