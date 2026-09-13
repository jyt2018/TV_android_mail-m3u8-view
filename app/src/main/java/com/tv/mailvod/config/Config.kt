package com.tv.mailvod.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * config.json 的内存模型。
 * 文件位于应用私有目录 files/config.json；旧版本的 mail 段因 ignoreUnknownKeys 会被忽略。
 */
@Serializable
data class Config(
    /** 片源地址(library.json 直链, 不限托管平台), 设置页可改。 */
    @SerialName("library_url")
    val libraryUrl: String = "https://gitee.com/unixsam/mailvod-release/raw/master/library.json",
    /** APK 更新检查地址(version.json 直链), 设置页可改; 缺省与片源同仓库。 */
    @SerialName("update_url")
    val updateUrl: String = "https://gitee.com/unixsam/mailvod-release/raw/master/version.json",
    @SerialName("list_columns")
    val listColumns: List<String> = listOf("title", "country", "type", "year", "director"),
    val player: Player = Player()
) {
    @Serializable
    data class Player(
        @SerialName("auto_next")
        val autoNext: Boolean = false
    )

    /** 旧配置可能残留 episode 列, 统一过滤; 全空时回退默认。 */
    val listColumnsNormalized: List<String>
        get() = listColumns.filter { it != "episode" }
            .ifEmpty { listOf("title", "country", "type", "year", "director") }
}
