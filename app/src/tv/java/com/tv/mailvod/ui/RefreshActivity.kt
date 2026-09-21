package com.tv.mailvod.ui

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.tv.mailvod.App
import com.tv.mailvod.R
import com.tv.mailvod.databinding.ActivityRefreshBinding
import com.tv.mailvod.net.LibrarySync
import kotlinx.coroutines.launch

/**
 * 刷新页: 说明文字 + 遥控器示意图 + 刷新按钮(默认焦点) + log 文本框。
 * - 进入后焦点落在"刷新"按钮; 遥控器菜单键在本页同样触发刷新
 * - 拉取片源地址 → 解析 → 合并, 过程逐行写入 log 文本框
 * - 返回片库页后其 onResume 会自动重载列表
 */
class RefreshActivity : ComponentActivity() {

    private lateinit var binding: ActivityRefreshBinding
    private val sync = LibrarySync()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.instance.configLoader.ensureLoaded()
        binding = ActivityRefreshBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { doRefresh() }
        // 默认焦点: 刷新按钮(遥控器进入页面即可 OK 触发)
        binding.btnRefresh.requestFocus()
    }

    /** 遥控器菜单键 = 直接刷新(与片库页一致的快捷行为)。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            doRefresh()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** log 文本框追加一行(首行不带换行)。 */
    private fun log(line: String) {
        binding.tvLog.append(if (binding.tvLog.text.isEmpty()) line else "\n$line")
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
