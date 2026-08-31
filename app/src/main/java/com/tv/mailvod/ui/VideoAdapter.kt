package com.tv.mailvod.ui

import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tv.mailvod.R
import com.tv.mailvod.config.Config
import com.tv.mailvod.databinding.ItemVideoBinding
import com.tv.mailvod.store.VideoItem
import kotlin.math.roundToInt

/**
 * 列表适配器: 每行 = 箭头固定列 | 编号固定列 | 字段列(固定宽度+weight混合) | 播放按钮 | 删除按钮。
 * 选中行 = rowRoot focused OR btnPlay focused OR btnDelete focused
 *         → rowRoot.isSelected=true(黄框保留) + 箭头显示 ">"
 * 通过 setHighlight(pos) 统一清除所有行高亮后再设置目标行,避免两行同时亮。
 *
 * 列宽策略(与表头完全一致 → 天然对齐):
 *   title    → weight=1 (自适应剩余空间)
 *   其他字段 → 固定 dp,按字符估算,超长截断
 */
class VideoAdapter(
    private val config: Config,
    private val onPlay: (VideoItem) -> Unit,
    private val onDelete: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    private val items = mutableListOf<VideoItem>()

    fun submit(list: List<VideoItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** 清除所有行高亮,只给 position 设置 (或 position=-1 全部清除)。 */
    fun setHighlight(rv: RecyclerView, position: Int) {
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val vh = rv.getChildViewHolder(child) as? VH ?: continue
            val pos = vh.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) continue
            val target = pos == position
            vh.binding.rowRoot.isSelected = target
        }
    }

    inner class VH(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.btnPlay.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onPlay(items[pos])
            }
            binding.btnDelete.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onDelete(items[pos])
            }

            // 方向键: row ↔ btnPlay ↔ btnDelete
            binding.rowRoot.nextFocusRightId = R.id.btnPlay
            binding.btnPlay.nextFocusRightId = R.id.btnDelete
            binding.btnPlay.nextFocusLeftId = R.id.rowRoot
            binding.btnDelete.nextFocusLeftId = R.id.btnPlay

            // 任何一个控件获得焦点时,统一刷新所有行高亮
            val focusTarget = { pos: Int ->
                val rv = binding.rowRoot.parent as? RecyclerView
                if (rv != null) setHighlight(rv, pos)
            }
            binding.rowRoot.onFocusChangeListener = View.OnFocusChangeListener { _, has ->
                if (has) focusTarget(bindingAdapterPosition)
            }
            binding.btnPlay.onFocusChangeListener = View.OnFocusChangeListener { _, has ->
                if (has) focusTarget(bindingAdapterPosition)
            }
            binding.btnDelete.onFocusChangeListener = View.OnFocusChangeListener { _, has ->
                if (has) focusTarget(bindingAdapterPosition)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val cols = config.listColumnsNormalized
        val context = parent.context
        val density = context.resources.displayMetrics.density
        val vh = VH(binding)
        cols.forEach { key ->
            val tv = TextView(context).apply {
                setTextColor(context.getColor(R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            val lp = buildColumnLayoutParams(key, density)
            tv.layoutParams = lp
            binding.llFields.addView(tv)
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.tvNum.text = item.displayId
        val cols = config.listColumnsNormalized
        for (i in cols.indices) {
            val tv = holder.binding.llFields.getChildAt(i) as TextView
            tv.text = item.columnValue(cols[i])
        }
        holder.binding.rowRoot.isSelected = false
    }

    override fun getItemCount(): Int = items.size

    companion object {
        /**
         * 列宽策略:
         *   title    → weight=1 (自适应)
         *   其他字段 → 固定 dp,按常见字符宽度估算
         *   与 ListActivity 表头完全一致,确保对齐
         */
        fun buildColumnLayoutParams(key: String, density: Float): LinearLayout.LayoutParams {
            val dp = fun(d: Int) = (d * density).roundToInt()
            return when (key) {
                "title" -> LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                "country" -> LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT)
                "type"    -> LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT)
                "year"    -> LinearLayout.LayoutParams(dp(50), LinearLayout.LayoutParams.WRAP_CONTENT)
                "director"-> LinearLayout.LayoutParams(dp(160), LinearLayout.LayoutParams.WRAP_CONTENT)
                "actors"  -> LinearLayout.LayoutParams(dp(160), LinearLayout.LayoutParams.WRAP_CONTENT)
                "episode" -> LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT)
                else      -> LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        }
    }
}
