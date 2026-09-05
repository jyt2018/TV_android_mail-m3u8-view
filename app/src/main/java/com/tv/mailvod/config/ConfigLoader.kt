package com.tv.mailvod.config

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 读取/持久化 config.json。
 * 路径：/data/data/<包名>/files/config.json
 * 零配置可用：不存在或损坏时使用默认值(内置 Gitee 片库地址), 设置页可改 library_url。
 * 旧版本遗留的 mail 段会被 ignoreUnknownKeys 忽略, 不影响读取。
 */
class ConfigLoader(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private val targetFile: File = File(context.filesDir, "config.json")

    @Volatile
    var config: Config = Config()
        private set

    /** 已存在则读取；不存在或损坏时返回默认配置。 */
    @Synchronized
    fun ensureLoaded(): Config {
        config = load()
        return config
    }

    /** 强制重新从磁盘读取（adb push 新文件后调用）。 */
    @Synchronized
    fun reload(): Config {
        config = load()
        return config
    }

    private fun load(): Config {
        if (!targetFile.exists()) return Config()
        return runCatching {
            json.decodeFromString(Config.serializer(), targetFile.readText())
        }.getOrElse {
            // 损坏时清空, 回退默认配置
            runCatching { targetFile.delete() }
            Config()
        }
    }

    /** 保存配置到磁盘（设置对话框调用）。 */
    fun save(c: Config) {
        synchronized(this) {
            targetFile.writeText(json.encodeToString(Config.serializer(), c))
            config = c
        }
    }
}
