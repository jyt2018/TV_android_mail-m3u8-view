package com.tv.mailvod.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 片源清单中的一个条目（library.json from Gitee / 本地 library.json 同构）。
 * 唯一键 = title；本地文件名/进度 key 均由 title 派生。
 */
@Serializable
data class VideoItem(
    val title: String,
    val year: Int? = null,
    val country: String? = null,
    val type: String? = null,
    val director: String? = null,
    val actors: String? = null,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** 入库时间戳（秒），列表按此降序（新片在上）。 */
    @SerialName("_added_at")
    val addedAt: Long = 0
) {
    /** 去重唯一键。 */
    val key: String get() = title

    /** 列表显示某字段值，未配置或空返回 "-"。 */
    fun columnValue(field: String): String = when (field) {
        "title" -> title
        "year" -> year?.toString() ?: "-"
        "country" -> country ?: "-"
        "type" -> type ?: "-"
        "director" -> director ?: "-"
        "actors" -> actors ?: "-"
        else -> "-"
    }
}
