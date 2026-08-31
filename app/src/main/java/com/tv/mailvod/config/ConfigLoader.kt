package com.tv.mailvod.config

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 读取/持久化 config.json。
 * 路径：/data/data/<包名>/files/config.json
 * 首次启动若不存在则从 assets 复制一份（带默认值与真实授权码）。
 *
 * 后续可用 adb push config.json /data/data/<包名>/files/ 覆盖更新。
 */
class ConfigLoader(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private val targetFile: File = File(context.filesDir, "config.json")

    @Volatile
    var config: Config = Config(Config.Mail())
        private set

    /** 首次启动从 assets 复制；已存在则直接读取。 */
    @Synchronized
    fun ensureLoaded(): Config {
        if (!targetFile.exists()) {
            runCatching {
                context.assets.open("config.json").use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
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
        return runCatching {
            val text = if (targetFile.exists()) targetFile.readText() else return Config(Config.Mail())
            json.decodeFromString(Config.serializer(), text)
        }.getOrElse {
            // 损坏时用 assets 重新生成
            runCatching {
                context.assets.open("config.json").use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val text = targetFile.takeIf { it.exists() }?.readText().orEmpty()
            if (text.isBlank()) Config(Config.Mail())
            else json.decodeFromString(Config.serializer(), text)
        }
    }

    /** 保存配置到磁盘（设置页预留，当前不编辑）。 */
    fun save(c: Config) {
        synchronized(this) {
            targetFile.writeText(json.encodeToString(Config.serializer(), c))
            config = c
        }
    }
}
