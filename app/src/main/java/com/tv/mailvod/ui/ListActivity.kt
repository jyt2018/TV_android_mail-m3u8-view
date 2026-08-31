package com.tv.mailvod.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tv.mailvod.App
import com.tv.mailvod.R
import com.tv.mailvod.databinding.ActivityListBinding
import com.tv.mailvod.mail.MailFetcher
import com.tv.mailvod.store.VideoItem
import kotlinx.coroutines.launch

/**
 * 列表页（唯一主页面）。
 * - onResume 加载 library.json 并显示
 * - 刷新键：IMAP 拉取 → 合并 → 刷新列表
 * - 行播放键：跳转 PlayerActivity（传 url + headers）
 * - 行删除键：系统 AlertDialog 二次确认 → 删除并刷新
 *
 * 遥控器焦点由 RecyclerView 视图树自然处理：上下行间、右进播放/删除键、到底部刷新键。
 */
class ListActivity : ComponentActivity() {

    private lateinit var binding: ActivityListBinding
    private lateinit var adapter: VideoAdapter
    private val fetcher = MailFetcher()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvTitle.text = getString(R.string.app_name)
        val ver = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("?")
        binding.tvVersion.text = "v$ver"

        val cfg = App.instance.configLoader.config
        adapter = VideoAdapter(
            config = cfg,
            onPlay = { item -> startPlayer(item) },
            onDelete = { item -> confirmDelete(item) }
        )
        binding.rvList.layoutManager = LinearLayoutManager(this)
        binding.rvList.adapter = adapter
        binding.rvList.itemAnimator?.changeDuration = 0

        // 动态填充表头字段名 (调用 VideoAdapter.buildColumnLayoutParams → 与表体列宽完全一致)
        val fieldLabelMap = mapOf(
            "title" to "标题",
            "episode" to "集",
            "year" to "年份",
            "country" to "国家",
            "type" to "类型",
            "director" to "导演",
            "actors" to "主演"
        )
        val cols = cfg.listColumnsNormalized
        val density = resources.displayMetrics.density
        cols.forEach { key ->
            val tv = android.widget.TextView(this).apply {
                text = fieldLabelMap[key] ?: key
                setTextColor(getColor(R.color.text_secondary))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            }
            tv.layoutParams = VideoAdapter.buildColumnLayoutParams(key, density)
            binding.llHeaderFields.addView(tv)
        }

        // 全局焦点监听: 焦点跳出 RecyclerView 时,清除所有行高亮
        binding.rvList.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            val rv = binding.rvList
            val inRv = newFocus != null && rv.indexOfChild(newFocus) >= 0
            if (!inRv) {
                adapter.setHighlight(rv, -1)
            }
        }

        binding.btnRefresh.setOnClickListener { doRefresh() }
    }

    override fun onResume() {
        super.onResume()
        loadList()
        if (App.instance.configLoader.config.mail.user.isBlank()) {
            Toast.makeText(this, "请先 adb push config.json", Toast.LENGTH_LONG).show()
        }
    }

    /** 遥控器菜单键(KEYCODE_MENU=82) = 刷新。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            doRefresh()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun loadList() {
        lifecycleScope.launch {
            val list = App.instance.library.load()
            adapter.submit(list)
            binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            binding.rvList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            updateTitle(list.size)
        }
    }

    private fun updateTitle(count: Int) {
        val base = getString(R.string.app_name)
        binding.tvTitle.text = if (count > 0) "$base (共$count)" else base
    }

    /** 拉取邮件并合并到 library.json。 */
    private fun doRefresh() {
        Toast.makeText(this, R.string.fetching, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val cfg = App.instance.configLoader.reload()
            val result = runCatching {
                val items = fetcher.fetch(cfg)
                val added = App.instance.library.merge(items)
                added
            }
            result.onSuccess { added ->
                Toast.makeText(this@ListActivity,
                    getString(R.string.fetch_done, added), Toast.LENGTH_SHORT).show()
                loadList()
            }.onFailure { e ->
                Toast.makeText(this@ListActivity,
                    getString(R.string.fetch_fail, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDelete(item: VideoItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete)
            .setMessage(getString(R.string.confirm_ok) + "\n\n" + item.title + if (item.episode > 0) " E${item.episode}" else "")
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    App.instance.library.delete(item.id)
                    loadList()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startPlayer(item: VideoItem) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, item.url)
            putExtra(PlayerActivity.EXTRA_TITLE,
                item.title + if (item.episode > 0) " E${item.episode}" else "")
            // headers 用 String[] 传递（keys/values 平行）
            val headers = item.headers
            putExtra(PlayerActivity.EXTRA_HEADER_KEYS, headers.keys.toTypedArray())
            putExtra(PlayerActivity.EXTRA_HEADER_VALS, headers.values.toTypedArray())
        }
        startActivity(intent)
    }
}
