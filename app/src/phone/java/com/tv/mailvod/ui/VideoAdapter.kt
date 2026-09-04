package com.tv.mailvod.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tv.mailvod.databinding.ItemVideoBinding
import com.tv.mailvod.store.VideoItem

/**
 * 手机版影片列表适配器（触屏）。
 * 行内三键: 在线播放 / 先下后播 / 删除；点标题区域 = 在线播放。
 * 已下载条目在标题右侧显示"已下载"标签。
 */
class VideoAdapter(
    private val onPlay: (VideoItem) -> Unit,
    private val onDownloadPlay: (VideoItem) -> Unit,
    private val onDelete: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    private val items = mutableListOf<VideoItem>()
    private val downloaded = mutableSetOf<String>()

    class VH(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding
        b.tvTitle.text = item.title + if (item.episode > 0) " E${item.episode}" else ""
        val meta = listOfNotNull(
            item.year?.toString(),
            item.country?.takeIf { it.isNotBlank() },
            item.type?.takeIf { it.isNotBlank() },
            item.director?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        b.tvMeta.text = meta
        b.tvMeta.visibility = if (meta.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        b.tvDownloaded.visibility =
            if (item.displayId in downloaded) android.view.View.VISIBLE else android.view.View.GONE
        b.tvTitle.setOnClickListener { onPlay(item) }
        b.btnPlay.setOnClickListener { onPlay(item) }
        b.btnDownload.setOnClickListener { onDownloadPlay(item) }
        b.btnDelete.setOnClickListener { onDelete(item) }
    }

    /** 全量提交列表。 */
    fun submit(list: List<VideoItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** 更新已下载集合并刷新标签。 */
    fun setDownloaded(ids: Set<String>) {
        downloaded.clear()
        downloaded.addAll(ids)
        notifyDataSetChanged()
    }
}
