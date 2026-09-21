package com.tv.mailvod.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tv.mailvod.App
import com.tv.mailvod.R
import com.tv.mailvod.databinding.ActivityRefreshBinding
import com.tv.mailvod.net.LibrarySync
import kotlinx.coroutines.launch

/**
 * 手机版刷新页: 说明文字 + 刷新按钮 + log 文本框。
 * - 拉取片源地址 → 解析 → 合并, 过程逐行写入 log 文本框
 * - 返回片库页后其 onResume 会自动重载列表
 */
class RefreshActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRefreshBinding
    private val sync = LibrarySync()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.instance.configLoader.ensureLoaded()
        binding = ActivityRefreshBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { doRefresh() }
    }

    /** log 文本框追加一行(首行不带换行)。 */
    private fun log(line: String) {
        val tv: TextView = binding.tvLog
        tv.append(if (tv.text.isEmpty()) line else "\n$line")
    }

    /** 拉取片源清单并合并, 过程写入 log; 期间禁用刷新按钮防连按。 */
    private fun doRefresh() {
        binding.btnRefresh.isEnabled = false
        lifecycleScope.launch {
            log(getString(R.string.refresh_log_connect))
            runCatching {
                val cfg = App.instance.configLoader.reload()
                val items = sync.fetch(cfg.libraryUrl)
                log(getString(R.string.refresh_log_total, items.size))
                val added = App.instance.library.merge(items)
                // 刷新成功: 片库页 onResume 时列表滚动到顶部
                ListActivity.pendingScrollTop = true
                log(getString(R.string.refresh_log_added, added))
                log(getString(R.string.refresh_log_done))
            }.onFailure { e ->
                log(getString(R.string.refresh_log_fail, e.message ?: e.javaClass.simpleName))
            }
            binding.btnRefresh.isEnabled = true
        }
    }
}
