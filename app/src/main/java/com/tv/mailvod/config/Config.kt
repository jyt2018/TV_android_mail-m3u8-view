package com.tv.mailvod.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * config.json 的内存模型。
 * 文件位于应用私有目录 files/config.json，首次启动从 assets 复制。
 */
@Serializable
data class Config(
    val mail: Mail = Mail(),
    @SerialName("list_columns")
    val listColumns: List<String> = listOf("title", "episode", "year", "director"),
    val player: Player = Player()
) {
    @Serializable
    data class Mail(
        val host: String = "imap.163.com",
        val port: Int = 993,
        val user: String = "",
        @SerialName("auth_code")
        val authCode: String = "",
        @SerialName("subject_prefix")
        val subjectPrefix: String = "[TV投递]",
        val folder: String = "INBOX"
    )

    @Serializable
    data class Player(
        @SerialName("auto_next")
        val autoNext: Boolean = false
    )

    val listColumnsNormalized: List<String>
        get() = listColumns.ifEmpty { listOf("title", "episode", "year", "director") }
}
