package com.tv.mailvod.store

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 读写 library.json（应用私有目录）。
 * - 合并去重：按 title+episode 唯一键，命中覆盖 url/headers 保留 _id/_added_at，未命中分配新 _id 插入头部。
 * - 删除：移除条目，_id 作废不回收。
 * - 列表按 _added_at 降序（最新在上）。
 *
 * 所有 IO 在 IO 调度器；读返回已排序副本，写加锁。
 */
class LibraryStore(context: Context) {

    private val file = File(context.filesDir, "library.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val lock = Any()

    /** 读取并按 added_at 降序返回。 */
    suspend fun load(): List<VideoItem> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!file.exists()) emptyList()
            else runCatching {
                json.decodeFromString<List<VideoItem>>(file.readText())
            }.getOrDefault(emptyList())
        }.sortedWith(compareByDescending<VideoItem> { it.addedAt }.thenByDescending { it.id })
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
            var maxId = current.maxOfOrNull { it.id } ?: 0L
            var added = 0
            val now = System.currentTimeMillis() / 1000

            for (item in newItems) {
                val existing = byKey[item.key]
                if (existing == null) {
                    // 新片：分配新 _id，插头部
                    maxId += 1
                    val fresh = item.copy(id = maxId, addedAt = now)
                    byKey[item.key] = fresh
                    added++
                } else {
                    // 命中：覆盖 url/headers 与元信息，保留原 _id 与 _added_at
                    byKey[item.key] = existing.copy(
                        url = item.url,
                        headers = item.headers,
                        title = item.title,
                        episode = item.episode,
                        year = item.year,
                        country = item.country,
                        director = item.director,
                        actors = item.actors
                    )
                }
            }

            val merged = byKey.values.sortedWith(compareByDescending<VideoItem> { it.addedAt }.thenByDescending { it.id })
            file.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(VideoItem.serializer()), merged))
            added
        }
    }

    /** 按 _id 删除一条。 */
    suspend fun delete(id: Long): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!file.exists()) return@withContext false
            val current = runCatching {
                json.decodeFromString<List<VideoItem>>(file.readText())
            }.getOrDefault(emptyList())
            val after = current.filterNot { it.id == id }
            val changed = after.size != current.size
            if (changed) {
                file.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(VideoItem.serializer()), after))
            }
            changed
        }
    }
}
