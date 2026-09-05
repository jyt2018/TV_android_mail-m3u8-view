package com.tv.mailvod.net

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 自动更新检查：启动时后台拉取 Gitee 上的 version.json,
 * 若远端 versionCode 更大则下载 APK 到 cacheDir 并做 md5 校验。
 * version.json 顶层为 tv 版字段(兼容旧包), "phone" 子对象为手机版; 按 channel 取段。
 * 任何失败都静默(仅日志), 不打扰正常使用。
 */
class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "UpdateChecker"

        /** version.json 在 Gitee 公开仓库的 raw 直链(仓库 mailvod-release, 只放 APK 与版本清单)。 */
        const val VERSION_URL = "https://gitee.com/unixsam/mailvod-release/raw/master/version.json"

        /** 更新通道: tv = version.json 顶层(旧格式), phone = "phone" 子对象。 */
        const val CHANNEL_TV = "tv"
        const val CHANNEL_PHONE = "phone"
    }

    @Serializable
    data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val apk: String,
        val md5: String = ""
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** 只拉远端版本清单(不下载); channel=tv/phone; 失败返回 null(网络/解析错误)。 */
    fun fetchRemoteVersion(channel: String = CHANNEL_TV): VersionInfo? =
        runCatching { fetchJson(channel) }.getOrElse {
            Log.w(TAG, "check skip: ${it.message}")
            null
        }

    /** 下载清单指向的 APK 并 md5 校验; 失败返回 null。 */
    fun downloadUpdate(info: VersionInfo): File? {
        val apk = runCatching { download(info.apk) }.getOrElse {
            Log.w(TAG, "download fail: ${it.message}")
            return null
        }
        if (info.md5.isNotBlank() && md5Of(apk) != info.md5.lowercase()) {
            Log.w(TAG, "md5 mismatch, drop update")
            apk.delete()
            return null
        }
        return apk
    }

    /** 检查并下载更新(阻塞网络 IO, 必须在后台线程调用); 成功返回待安装的 APK 文件, 否则 null。 */
    fun checkAndDownload(channel: String = CHANNEL_TV): File? {
        val local = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }.getOrDefault(0)
        val info = fetchRemoteVersion(channel) ?: return null
        if (info.versionCode <= local) return null
        Log.i(TAG, "found update ${info.versionName} (code=${info.versionCode})")
        return downloadUpdate(info)
    }

    /** 本地 versionCode 是否已 >= 远端(外部 UI 判断用)。 */
    private fun fetchJson(channel: String): VersionInfo {
        val root = json.decodeFromString(
            kotlinx.serialization.json.JsonObject.serializer(),
            String(httpGet(VERSION_URL), Charsets.UTF_8)
        )
        val node = if (channel == CHANNEL_PHONE) {
            root["phone"] ?: throw java.io.IOException("version.json 缺少 phone 段")
        } else {
            root // tv: 顶层即 tv 字段(旧格式)
        }
        return json.decodeFromString(VersionInfo.serializer(), node.toString())
    }

    private fun download(urlStr: String): File {
        val out = File(context.cacheDir, "update.apk")
        out.outputStream().use { fos ->
            open(urlStr).inputStream.use { it.copyTo(fos) }
        }
        return out
    }

    private fun open(urlStr: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "mailvod-tv")
        return conn
    }

    private fun httpGet(urlStr: String): ByteArray {
        val conn = open(urlStr)
        val code = conn.responseCode
        if (code !in 200..299) throw java.io.IOException("HTTP $code")
        return conn.inputStream.use { it.readBytes() }
    }

    private fun md5Of(f: File): String {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
