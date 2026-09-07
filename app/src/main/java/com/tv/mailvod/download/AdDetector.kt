package com.tv.mailvod.download

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import org.json.JSONArray

/**
 * HLS 广告分片检测器 (PTS 时间轴法, 唯一算法, 通用)。
 *
 * 原理 (量子 lz-cdn 源与非凡 ffzy 源均已实测, 判定与文件名法完全一致):
 * 播放清单以 #EXT-X-DISCONTINUITY 把正片切成组, 广告以整组硬拼接, 其 PTS
 * (展示时间戳) 脱离内容时间轴; 正片各组 PTS 链严格连续 (整片漂移仅 0.1 秒)。
 * 检测: HTTP Range 取每组首分片头部 64KB, 解析首个 PES 的 PTS, 沿清单
 * 累计时长推进预期起点, 命中 (容差 2 秒) 判正片, 脱轨判广告。
 *
 * 护栏 (任一超限视为误判, 放弃剔除按原样下载):
 * 广告块 ≤5; 单块 ≤4 组且 ≤120 秒; 广告总时长 ≤15%。
 * 扫描失败的组按正片保留 (部分剔除, 宁多勿缺)。
 */
internal object AdDetector {

    private const val TOLERANCE = 2.0        // PTS 命中容差 (秒)
    private const val MAX_BLOCKS = 5         // 广告块数上限
    private const val MAX_BLOCK_GROUPS = 4   // 单广告块组数上限
    private const val MAX_BLOCK_SECONDS = 120.0 // 单广告块时长上限 (秒)
    private const val MAX_AD_RATIO = 0.15    // 广告总时长占比上限

    /**
     * 检测广告组。
     * @param groups 每组 = 分片索引列表 (按不连续标记切分)
     * @param segDur 每个分片的 EXTINF 时长 (秒)
     * @param groupFirst 每组首个分片的 URL
     * @param cacheDir 检测结果缓存目录 (按清单指纹命名), null = 不缓存
     * @param fingerprint 播放清单内容指纹 (SHA-256)
     * @param head 抓取 URL 头部 64KB 字节, 失败返回 null
     * @param onScan 扫描进度回调 (已完成组数, 总组数)
     * @return 待剔除的分片索引集合; 空集 = 无广告; null = 放弃剔除
     */
    fun detect(
        groups: List<List<Int>>,
        segDur: List<Double>,
        groupFirst: List<String>,
        cacheDir: File?,
        fingerprint: String,
        head: (String) -> ByteArray?,
        onScan: (Int, Int) -> Unit
    ): Set<Int>? {
        if (groups.size < 2) return null // 无不连续分组, 时间轴法不适用

        // 缓存命中: 直接复用历史检测结果 (指纹相同 = 清单相同)
        cacheDir?.let { dir ->
            val f = File(dir, cacheName(fingerprint))
            if (f.isFile) {
                runCatching {
                    val arr = JSONArray(f.readText())
                    val adGroups = (0 until arr.length())
                        .map { arr.getInt(it) }
                        .filter { it in groups.indices }
                    return toSegmentSet(adGroups, groups)
                }
            }
        }

        // 8 线程并发扫描各组首分片头部, 解析 PTS
        val n = groups.size
        val pts = DoubleArray(n) { Double.NaN }
        val done = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(8)
        val futures = (0 until n).map { gi ->
            pool.submit {
                try {
                    head(groupFirst[gi])?.let { data ->
                        firstPts(data)?.let { pts[gi] = it }
                    }
                } catch (_: Exception) {
                    // 单组失败按正片保留
                }
                onScan(done.incrementAndGet(), n)
            }
        }
        futures.forEach { it.get() }
        pool.shutdown()
        if (pts[0].isNaN()) return null // 首组失败, 无法锚定内容时间轴起点

        // 分类: 命中预期判正片, 脱轨判广告; 扫描失败组按正片保留,
        // 其后正片命中时用双假设消歧 (未知组算正片或算广告两种预期都试)
        val groupDur = DoubleArray(n) { g -> groups[g].sumOf { segDur[it] } }
        val isAd = BooleanArray(n)
        val offset = pts[0] // 组 0 为正片, 定义内容时间轴偏移
        var contentSum = groupDur[0]
        var unknownSum = 0.0
        val unknownGroups = mutableListOf<Int>()
        for (gi in 1 until n) {
            val p = pts[gi]
            if (p.isNaN()) {
                unknownSum += groupDur[gi]
                unknownGroups.add(gi)
                continue
            }
            when {
                abs(p - contentSum - unknownSum - offset) <= TOLERANCE -> {
                    contentSum += groupDur[gi] + unknownSum
                    unknownGroups.clear()
                    unknownSum = 0.0
                }
                unknownGroups.isNotEmpty() && abs(p - contentSum - offset) <= TOLERANCE -> {
                    unknownGroups.forEach { isAd[it] = true } // 回溯标记, 下载时仍保留
                    unknownGroups.clear()
                    unknownSum = 0.0
                    contentSum += groupDur[gi]
                }
                else -> isAd[gi] = true
            }
        }

        // 护栏校验
        var adSec = 0.0
        var blockCount = 0
        var bi = 1
        while (bi < n) {
            if (isAd[bi]) {
                var bj = bi
                var sec = 0.0
                while (bj < n && isAd[bj]) {
                    sec += groupDur[bj]
                    bj++
                }
                if (++blockCount > MAX_BLOCKS) return null
                if (bj - bi > MAX_BLOCK_GROUPS || sec > MAX_BLOCK_SECONDS) return null
                adSec += sec
                bi = bj
            } else {
                bi++
            }
        }
        if (adSec > groupDur.sum() * MAX_AD_RATIO) return null

        // 缓存并返回 (空集 = 无广告, 同样缓存避免重复扫描)
        val adGroups = (0 until n).filter { isAd[it] }
        cacheDir?.let { dir ->
            runCatching {
                dir.mkdirs()
                val json = JSONArray()
                adGroups.forEach { json.put(it) }
                File(dir, cacheName(fingerprint)).writeText(json.toString())
            }
        }
        return toSegmentSet(adGroups, groups)
    }

    private fun toSegmentSet(adGroups: List<Int>, groups: List<List<Int>>): Set<Int> =
        adGroups.flatMapTo(mutableSetOf()) { groups[it] }

    private fun cacheName(fingerprint: String) = "ad_${fingerprint.take(16)}.json"

    /** 解析 TS 字节流中首个带 PTS 的 PES 包的展示时间戳 (90kHz 时钟, 秒), 失败返回 null。 */
    private fun firstPts(ts: ByteArray): Double? {
        var i = 0
        while (i + 188 <= ts.size) {
            if (ts[i].toInt() and 0xFF != 0x47) { // 同步字节, 逐字节重找
                i++
                continue
            }
            val pusi = (ts[i + 1].toInt() shr 6) and 1
            val afc = (ts[i + 3].toInt() shr 4) and 3
            if (pusi == 1 && afc and 1 != 0) {
                var p = i + 4
                if (afc and 2 != 0) p += 1 + (ts[p].toInt() and 0xFF) // 跳过自适应字段
                if (p + 14 <= i + 188
                    && ts[p].toInt() and 0xFF == 0
                    && ts[p + 1].toInt() and 0xFF == 0
                    && ts[p + 2].toInt() and 0xFF == 1
                    && ts[p + 7].toInt() and 0x80 != 0
                ) {
                    val q = p + 9
                    val pts = ((ts[q].toInt() and 0xFF shr 1) and 7).toLong() shl 30 or
                        ((ts[q + 1].toInt() and 0xFF).toLong() shl 22) or
                        ((ts[q + 2].toInt() and 0xFF shr 1).toLong() shl 15) or
                        ((ts[q + 3].toInt() and 0xFF).toLong() shl 7) or
                        ((ts[q + 4].toInt() and 0xFF shr 1).toLong())
                    return pts / 90000.0
                }
            }
            i += 188
        }
        return null
    }
}
