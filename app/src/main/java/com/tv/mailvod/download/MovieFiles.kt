package com.tv.mailvod.download

import android.content.Context
import com.tv.mailvod.store.VideoItem
import java.io.File

/**
 * 本地影片文件管理（TV 与 phone 共用）。
 * 目录: app 外部私有目录 movies/；拼接产物 = 片名.ts（片名做文件名安全化）；临时分片目录 = 片名_tmp/。
 */
object MovieFiles {

    /** 文件名非法字符（Windows/Android 通用）。 */
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|]")

    /** 本地影片目录 (app 外部私有目录 movies/)。 */
    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "movies").apply { mkdirs() }

    /** 片名 → 本地文件基名（去首尾空白、非法字符换下划线）。 */
    fun keyOf(title: String): String =
        title.trim().replace(ILLEGAL, "_").ifEmpty { "untitled" }

    /** 已下载条目 (片名.ts 拼接产物) 的基名集合。 */
    fun downloadedKeys(ctx: Context): Set<String> =
        dir(ctx).listFiles { f -> f.isFile && f.extension == "ts" }
            ?.mapTo(HashSet()) { it.nameWithoutExtension } ?: emptySet()

    /** 条目对应的本地播放文件 (片名.ts)，无则 null。 */
    fun localFileFor(ctx: Context, item: VideoItem): File? {
        val ts = File(dir(ctx), keyOf(item.title) + ".ts")
        return if (ts.exists() && ts.length() > 100 * 1024) ts else null
    }

    /** 拼接产物目标文件 (片名.ts)。 */
    fun outFile(ctx: Context, item: VideoItem): File = File(dir(ctx), keyOf(item.title) + ".ts")

    /** 下载临时分片目录 (片名_tmp/)。 */
    fun tmpDir(ctx: Context, item: VideoItem): File = File(dir(ctx), keyOf(item.title) + "_tmp")

    /** 删除条目对应的本地文件 (片名.ts/mp4 + 临时分片目录)。 */
    fun deleteLocalFiles(ctx: Context, title: String) {
        val base = keyOf(title)
        dir(ctx).listFiles { f -> f.isFile && f.nameWithoutExtension == base }
            ?.forEach { it.delete() }
        File(dir(ctx), "${base}_tmp").deleteRecursively()
    }

    /**
     * 旧版"编号.ts"产物改名"片名.ts"（幂等：目标已存在或源不存在则跳过）。
     * @param pairs 旧 (编号, 片名) 对，编号按 %04d 还原旧文件名。
     */
    fun migrateLegacy(ctx: Context, pairs: List<Pair<Long, String>>) {
        val d = dir(ctx)
        for ((id, title) in pairs) {
            val oldBase = id.toString().padStart(4, '0')
            val newBase = keyOf(title)
            if (oldBase == newBase) continue
            for (ext in listOf("ts", "mp4")) {
                val old = File(d, "$oldBase.$ext")
                val new = File(d, "$newBase.$ext")
                if (old.exists() && !new.exists()) old.renameTo(new)
            }
            val oldTmp = File(d, "${oldBase}_tmp")
            val newTmp = File(d, "${newBase}_tmp")
            if (oldTmp.isDirectory && !newTmp.exists()) oldTmp.renameTo(newTmp)
        }
    }
}
