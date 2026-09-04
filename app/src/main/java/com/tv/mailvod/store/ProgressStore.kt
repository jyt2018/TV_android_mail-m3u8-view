package com.tv.mailvod.store

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 本地播放进度存取: files/progress.json, key = 片名(+集数)。
 * 与 library.json 解耦 — 片库 merge/重建不影响进度; 覆盖安装保留, 卸载才清。
 * 看完(>=98% 或剩余<30s)的条目自动清除, 下次从头播。
 */
class ProgressStore(context: Context) {

    @Serializable
    data class Entry(val positionMs: Long, val durationMs: Long, val updatedAt: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(context.filesDir, "progress.json")

    /** 进度条目 key: 片名(+ " E集数")。与 PlayerActivity.EXTRA_TITLE 的构造保持一致。 */
    companion object {
        fun keyOf(title: String, episode: Int): String =
            title + if (episode > 0) " E$episode" else ""

        /** 是否视为看完。durationMs<=0 (未知) 时不算。 */
        fun isFinished(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && (positionMs >= durationMs - 30_000 || positionMs * 100 >= durationMs * 98)
    }

    @Synchronized
    fun get(key: String): Entry? {
        val all = loadAll()
        return all[key]
    }

    /** 保存进度; 已看完则清除该条目。 */
    @Synchronized
    fun save(key: String, positionMs: Long, durationMs: Long) {
        if (positionMs <= 0) return
        val all = loadAll().toMutableMap()
        if (isFinished(positionMs, durationMs)) {
            all.remove(key)
        } else {
            all[key] = Entry(positionMs, durationMs, System.currentTimeMillis())
        }
        writeAll(all)
    }

    /** 删除条目时清理对应进度。 */
    @Synchronized
    fun remove(key: String) {
        val all = loadAll().toMutableMap()
        if (all.remove(key) != null) writeAll(all)
    }

    private fun loadAll(): Map<String, Entry> = runCatching {
        if (!file.exists()) return emptyMap()
        json.decodeFromString<Map<String, Entry>>(file.readText())
    }.getOrDefault(emptyMap())

    private fun writeAll(all: Map<String, Entry>) {
        runCatching {
            file.writeText(json.encodeToString(
                MapSerializer(String.serializer(), Entry.serializer()), all))
        }
    }
}
