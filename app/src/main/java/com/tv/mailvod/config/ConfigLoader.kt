package com.tv.mailvod.config

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 读取/持久化 config.json。
 * 路径：/data/data/<包名>/files/config.json
 * 首次启动不存在 → config.user 为空 → ListActivity 跳转 SetupActivity 由用户遥控器输入。
 * APK 不再打包 assets/config.json（防逆向泄露授权码）。
 *
 * 后续可用 adb push config.json /data/data/<包名>/files/ 覆盖更新。
 */
class ConfigLoader(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private val targetFile: File = File(context.filesDir, "config.json")

    @Volatile
    var config: Config = Config(Config.Mail())
        private set

    /** 已存在则读取；不存在返回空配置(user 为空, 由 SetupActivity 填写)。 */
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
        return runCatching {
            val text = if (targetFile.exists()) targetFile.readText() else return Config(Config.Mail())
            json.decodeFromString(Config.serializer(), text)
        }.getOrElse {
            // 损坏时清空重写, 让用户重新走 SetupActivity
            runCatching { targetFile.delete() }
            Config(Config.Mail())
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
