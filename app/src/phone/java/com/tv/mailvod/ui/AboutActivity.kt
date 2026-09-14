package com.tv.mailvod.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tv.mailvod.App
import com.tv.mailvod.R
import com.tv.mailvod.databinding.ActivityAboutBinding
import com.tv.mailvod.download.MovieFiles
import com.tv.mailvod.net.AppUpdater
import com.tv.mailvod.net.UpdateChecker
import kotlinx.coroutines.launch

/**
 * 手机版关于页: 版本、开发者、下载统计(已下载部数/占用/剩余空间)、操作说明、检查更新按钮。
 * 由片库页左上角标题进入。
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding
    private val updater = AppUpdater(this, UpdateChecker.CHANNEL_PHONE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnCheck.setOnClickListener { updater.check(manual = true) }
        loadInfo()
    }

    /** 版本信息同步填充; 下载统计(目录遍历)异步计算后填充。 */
    private fun loadInfo() {
        val info = packageManager.getPackageInfo(packageName, 0)
        binding.tvVersionInfo.text =
            getString(R.string.about_developer) + "\n" +
            getString(R.string.about_version, "v ${info.versionName} (${info.versionCode})")
        lifecycleScope.launch {
            val items = App.instance.library.load()
            val ids = MovieFiles.downloadedKeys(this@AboutActivity)
            val titles = items.filter { MovieFiles.keyOf(it.title) in ids }.map { it.title }.toSortedSet()
            val dir = MovieFiles.dir(this@AboutActivity)
            val usedBytes = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            val freeBytes = dir.freeSpace
            binding.tvStats.text =
                getString(R.string.about_downloaded, titles.size) + "\n" +
                getString(R.string.about_used, fmtGb(usedBytes)) + "\n" +
                getString(R.string.about_free, fmtGb(freeBytes))
        }
    }

    /** 字节转 GB 字符串, 两位小数。 */
    private fun fmtGb(bytes: Long): String =
        String.format(java.util.Locale.US, "%.2f", bytes / 1073741824.0)
}
