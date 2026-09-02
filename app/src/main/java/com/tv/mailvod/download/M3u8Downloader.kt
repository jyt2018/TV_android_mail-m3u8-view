package com.tv.mailvod.download

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * M3U8 下载引擎 (移植自 H:\downmovie\script\m3u8_download.py)。
 *
 * 流程: 获取 m3u8 (master→子列表) → 广告检测/校验/去除 (仅量子 lz 源)
 *      → 多线程下载 TS 分片 (断点续传 + AES-128 解密) → 二进制拼接 TS
 *      → 重命名 TS 为最终产物 (ExoPlayer 原生支持 MPEG-TS, 跳过重封装)。
 *
 * 盒子无 ffmpeg, 曾用 MediaMuxer 重封装 MP4 但慢且易失败, 现直接输出 TS;
 * 分片下载 8 线程 (盒子性能友好)。
 *
 * @param m3u8Url  播放列表 URL
 * @param headers  防盗链 headers (来自邮件条目, 可为空 map)
 * @param workDir  临时分片目录 (下载完成后自动清理)
 * @param outFile  最终输出文件 (编号.ts; 扩展名由调用方给定, 实际写出 .ts)
 * @param listener 进度/结果回调 (全部在工作线程回调, UI 层需自行切主线程)
 */
class M3u8Downloader(
    private val m3u8Url: String,
    private val headers: Map<String, String>,
    private val workDir: File,
    private val outFile: File,
    private val listener: Listener
) {

    interface Listener {
        /** 阶段文本 + 总进度百分比 (0-100); indeterminate=true 时 UI 显示流动进度条。 */
        fun onProgress(stage: String, percent: Int, indeterminate: Boolean = false)

        /** 下载合并完成, file 为实际可播放文件 (mp4 或回退 ts)。 */
        fun onDone(file: File)

        fun onError(message: String)
    }

    /** EXT-X-KEY 信息 (AES-128)。 */
    private class KeyInfo(val uri: String, val ivHex: String?)

    /** 一个 TS 分片。 */
    private class Segment(val url: String, val key: KeyInfo?, val seq: Long)

    @Volatile
    private var cancelled = false

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 取消下载 (幂等, 完成后调用无副作用)。 */
    fun cancel() {
        cancelled = true
    }

    /** 启动下载线程, 结果经 listener 返回。 */
    fun start() {
        Thread({
            try {
                run()
            } catch (t: Throwable) {
                if (!cancelled) listener.onError(t.message ?: t.javaClass.simpleName)
            }
        }, "m3u8-download").start()
    }

    // ═══════════════════════ 主流程 ═══════════════════════

    private fun run() {
        workDir.mkdirs()

        listener.onProgress("正在解析播放列表…", 0)
        var listUrl = m3u8Url
        var text = String(httpGet(listUrl), Charsets.UTF_8)

        // master playlist → 解析子列表 (取第一个)
        if (text.contains("#EXT-X-STREAM-INF")) {
            var subRef: String? = null
            val lines = text.split('\n')
            for (i in lines.indices) {
                if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                    for (j in i + 1 until lines.size) {
                        val c = lines[j].trim()
                        if (c.isNotEmpty() && !c.startsWith("#")) { subRef = c; break }
                    }
                    break
                }
            }
            subRef ?: throw IOException("master playlist 中未找到子列表")
            listUrl = resolveUrl(listUrl, subRef)
            text = String(httpGet(listUrl), Charsets.UTF_8)
        }

        // 解析分片 + AES-128 key
        var mediaSeq = 0L
        var curKey: KeyInfo? = null
        val segments = mutableListOf<Segment>()
        for (raw in text.split('\n')) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    mediaSeq = line.substringAfter(':').toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-KEY") && line.contains("METHOD=AES-128") -> {
                    val uri = Regex("URI=[\"']([^\"']+)[\"']").find(line)?.groupValues?.get(1)
                    if (uri != null) {
                        val iv = Regex("IV=0[xX]([0-9a-fA-F]{32})").find(line)?.groupValues?.get(1)
                        curKey = KeyInfo(resolveUrl(listUrl, uri), iv)
                    }
                }
                line.startsWith("#EXT-X-KEY") && line.contains("METHOD=NONE") -> curKey = null
                line.isNotEmpty() && !line.startsWith("#") ->
                    segments.add(Segment(resolveUrl(listUrl, line), curKey, mediaSeq++))
            }
        }
        if (segments.isEmpty()) throw IOException("播放列表中无 TS 分片")

        // 广告检测 (仅量子 lz 源), 返回 null = 检测关闭或误判跳过
        val host = URL(listUrl).host.lowercase()
        val adIdx = if (host.contains("lz")) detectAds(segments) else null
        val finalSegs = if (adIdx.isNullOrEmpty()) segments
        else segments.filterIndexed { i, _ -> i !in adIdx }

        val total = finalSegs.size

        // 多线程下载分片
        val ok = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(8)
        val futures = finalSegs.mapIndexed { idx, seg ->
            pool.submit {
                if (downloadSegment(idx, seg)) {
                    val done = ok.incrementAndGet()
                    listener.onProgress(
                        "正在下载分片 $done/$total",
                        (done * 100L / total).toInt()
                    )
                }
            }
        }
        pool.shutdown()
        futures.forEach { it.get() }
        val success = ok.get()
        if (cancelled) return
        if (success == 0) throw IOException("全部 $total 个分片下载失败")

        // 拼接 TS → 直接作为最终产物 (ExoPlayer 原生支持 MPEG-TS, 跳过重封装省几分钟且不会失败)
        listener.onProgress("正在拼接 TS…", 0, true)
        val mergedTs = File(workDir.parentFile, workDir.name + "_all.ts")
        BufferedOutputStream(FileOutputStream(mergedTs), 1 shl 20).use { out ->
            for (i in 0 until total) {
                val part = File(workDir, "$i.ts")
                if (part.length() > 0) part.inputStream().use { it.copyTo(out, 1 shl 16) }
            }
        }
        if (mergedTs.length() < 100 * 1024) {
            mergedTs.delete()
            throw IOException("拼接结果异常 (${mergedTs.length()} 字节)")
        }
        val tsOut = File(outFile.parentFile, outFile.nameWithoutExtension + ".ts")
        if (!mergedTs.renameTo(tsOut)) {
            // 同分区 rename 基本不会失败, 兜底再试一次拷贝
            mergedTs.copyTo(tsOut, overwrite = true)
            mergedTs.delete()
        }
        if (cancelled) {
            tsOut.delete()
            return
        }

        // 清理临时分片
        workDir.listFiles()?.forEach { it.delete() }
        workDir.delete()

        listener.onDone(tsOut)
    }

    // ═══════════════════════ 分片下载 ═══════════════════════

    /** 下载单个分片 (断点续传 + 重试 + AES-128 解密)。 */
    private fun downloadSegment(idx: Int, seg: Segment): Boolean {
        val f = File(workDir, "$idx.ts")
        if (f.exists() && f.length() > 0) return true
        repeat(5) {
            if (cancelled) return false
            try {
                var data = httpGet(seg.url)
                val k = seg.key
                if (k != null) data = decrypt(data, k, seg.seq)
                if (data.isNotEmpty()) {
                    f.writeBytes(data)
                    return true
                }
            } catch (_: Exception) {
                // 重试
            }
        }
        return false
    }

    /** AES-128-CBC 解密, IV 未声明时取媒体序号大端 16 字节 (HLS 规范默认)。 */
    private fun decrypt(data: ByteArray, key: KeyInfo, seq: Long): ByteArray {
        val keyBytes = httpGet(key.uri)
        val iv = key.ivHex?.let { hexToBytes(it) }
            ?: ByteArray(16) { i -> (seq shr ((15 - i) * 8)).toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    // ═══════════════════════ 广告检测 (量子 lz 源) ═══════════════════════

    /**
     * 量子源 TS 文件名数字递增, 相邻不连续处翻转广告状态 (同 python 版算法)。
     * 校验: >6 组或占比 >30% 判为误判, 返回 null 跳过去除。
     */
    private fun detectAds(segments: List<Segment>): Set<Int>? {
        val nums = segments.map { tsNumber(it.url) }
        val adIdx = mutableSetOf<Int>()
        val groups = mutableListOf<Int>()
        var adIn = false
        var curLen = 0
        for (j in 1 until nums.size) {
            val p = nums[j - 1]
            val c = nums[j]
            if (p != null && c != null && c != p + 1) {
                adIn = !adIn
                if (adIn) curLen = 0 else { groups.add(curLen); curLen = 0 }
            }
            if (adIn) { adIdx.add(j); curLen++ }
        }
        if (adIn && curLen > 0) groups.add(curLen)

        if (groups.isEmpty()) return null
        if (groups.size > 6) return null
        if (adIdx.size * 100 > segments.size * 30) return null
        return adIdx
    }

    /** 从 TS 文件名末尾提取连续数字, 例如 '4e2620cf1d64721621.ts' → 64721621。 */
    private fun tsNumber(url: String): Long? {
        val base = url.substringAfterLast('/')
        Regex("(\\d+)\\.ts$", RegexOption.IGNORE_CASE).find(base)?.let {
            return it.groupValues[1].toLongOrNull()
        }
        val digits = base.filter { c -> c.isDigit() }
        return if (digits.isEmpty()) null else digits.toLongOrNull()
    }

    // ═══════════════════════ HTTP 工具 ═══════════════════════

    private fun httpGet(urlStr: String): ByteArray {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept", "*/*")
                headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code: $urlStr")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn?.disconnect()
        }
    }

    private fun resolveUrl(base: String, ref: String): String = URL(URL(base), ref).toString()

    private fun hexToBytes(hex: String): ByteArray {
        val h = hex.removePrefix("0x").removePrefix("0X")
        return ByteArray(h.length / 2) { i ->
            ((Character.digit(h[i * 2], 16) shl 4) + Character.digit(h[i * 2 + 1], 16)).toByte()
        }
    }
}
