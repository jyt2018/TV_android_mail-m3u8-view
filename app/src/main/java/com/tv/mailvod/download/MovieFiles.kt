package com.tv.mailvod.download

import android.content.Context
import com.tv.mailvod.store.VideoItem
import java.io.File

/**
 * 本地影片文件管理（TV 与 phone 共用）。
 * 目录: app 外部私有目录 movies/；拼接产物 = 编号.ts；临时分片目录 = 编号_tmp/。
 */
object MovieFiles {

    /** 本地影片目录 (app 外部私有目录 movies/)。 */
    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "movies").apply { mkdirs() }

    /** 已下载条目 (编号.ts 拼接产物) 的 displayId 集合。 */
    fun downloadedIds(ctx: Context): Set<String> =
        dir(ctx).listFiles { f -> f.isFile && f.extension == "ts" }
            ?.mapTo(HashSet()) { it.nameWithoutExtension } ?: emptySet()

    /** 条目对应的本地播放文件 (编号.ts)，无则 null。 */
    fun localFileFor(ctx: Context, item: VideoItem): File? {
        val ts = File(dir(ctx), "${item.displayId}.ts")
        return if (ts.exists() && ts.length() > 100 * 1024) ts else null
    }

    /** 删除条目对应的本地文件 (编号.ts/mp4 + 临时分片目录)。 */
    fun deleteLocalFiles(ctx: Context, displayId: String) {
        dir(ctx).listFiles { f -> f.isFile && f.nameWithoutExtension == displayId }
            ?.forEach { it.delete() }
        File(dir(ctx), "${displayId}_tmp").deleteRecursively()
    }
}
