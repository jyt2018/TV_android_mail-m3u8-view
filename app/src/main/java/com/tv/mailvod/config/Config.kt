package com.tv.mailvod.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * config.json 的内存模型。
 * 文件位于应用私有目录 files/config.json；旧版本的 mail 段因 ignoreUnknownKeys 会被忽略。
 */
@Serializable
data class Config(
    /** 片库地址(Gitee 仓库 library.json 的 raw 直链), 设置页可改。 */
    @SerialName("library_url")
    val libraryUrl: String = "https://gitee.com/unixsam/mailvod-release/raw/master/library.json",
    @SerialName("list_columns")
    val listColumns: List<String> = listOf("title", "episode", "year", "director"),
    val player: Player = Player()
) {
    @Serializable
    data class Player(
        @SerialName("auto_next")
        val autoNext: Boolean = false
    )

    val listColumnsNormalized: List<String>
        get() = listColumns.ifEmpty { listOf("title", "episode", "year", "director") }
}
