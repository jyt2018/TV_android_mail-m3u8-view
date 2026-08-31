package com.tv.mailvod.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * library.json 中的一个条目。对应一封 [TV投递] 邮件。
 * 唯一键 = title + episode（episode 缺省按 0）。
 */
@Serializable
data class VideoItem(
    @SerialName("_id")
    val id: Long,
    val title: String,
    val episode: Int = 0,
    val year: Int? = null,
    val country: String? = null,
    val type: String? = null,
    val director: String? = null,
    val actors: String? = null,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    @SerialName("_added_at")
    val addedAt: Long
) {
    /** 列表显示用的编号（4 位补零）。 */
    val displayId: String get() = id.toString().padStart(4, '0')

    /** 去重唯一键。 */
    val key: String get() = "$title|$episode"

    /** 列表显示某字段值，未配置或空返回 "-"。 */
    fun columnValue(field: String): String = when (field) {
        "title" -> title
        "episode" -> if (episode > 0) episode.toString() else "-"
        "year" -> year?.toString() ?: "-"
        "country" -> country ?: "-"
        "type" -> type ?: "-"
        "director" -> director ?: "-"
        "actors" -> actors ?: "-"
        else -> "-"
    }
}
