package com.tv.mailvod.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tv.mailvod.App
import com.tv.mailvod.R
import com.tv.mailvod.databinding.ActivityListBinding
import com.tv.mailvod.download.M3u8Downloader
import com.tv.mailvod.download.MovieFiles
import com.tv.mailvod.mail.MailFetcher
import com.tv.mailvod.store.ProgressStore
import com.tv.mailvod.store.VideoItem
import kotlinx.coroutines.launch
import java.io.File

/**
 * 手机版列表主页面（触屏）。
 * - 首次安装(无邮箱配置) → 弹设置对话框
 * - 刷新按钮: IMAP 拉取 → 合并 → 刷新列表
 * - 行内: 在线播放 / 先下后播(进度对话框) / 删除(二次确认)
 * 播放核心、文件管理与 TV 版共用(VodPlayer/MovieFiles), 本类只做触屏交互。
 */
class ListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding
    private lateinit var adapter: VideoAdapter
    private val fetcher = MailFetcher()
    private var downloader: M3u8Downloader? = null
    private val updater = com.tv.mailvod.net.AppUpdater(
        this, com.tv.mailvod.net.UpdateChecker.CHANNEL_PHONE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.instance.configLoader.ensureLoaded()
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvTitle.text = getString(R.string.app_name)
        val ver = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("?")
        binding.tvVersion.text = "v $ver"

        adapter = VideoAdapter(
            onPlay = { item -> startPlayer(item) },
            onDownloadPlay = { item -> downloadThenPlay(item) },
            onDelete = { item -> confirmDelete(item) }
        )
        binding.rvList.layoutManager = LinearLayoutManager(this)
        binding.rvList.adapter = adapter
        binding.rvList.itemAnimator?.changeDuration = 0

        binding.btnRefresh.setOnClickListener { doRefresh() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }

        // 首次安装(无配置) → 弹邮箱设置; 保存后自动刷新
        if (App.instance.configLoader.config.mail.user.isBlank()) {
            showSettingsDialog()
        } else {
            doRefresh() // 启动自动刷新一次
        }
    }

    override fun onResume() {
        super.onResume()
        loadList()
        // Gitee 自动更新(与 TV 共用 AppUpdater, 30 分钟节流)
        if (updater.shouldAutoCheck()) updater.check(manual = false)
    }

    private fun loadList() {
        lifecycleScope.launch {
            val list = App.instance.library.load()
            adapter.submit(list)
            adapter.setDownloaded(MovieFiles.downloadedIds(this@ListActivity))
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
        if (App.instance.configLoader.config.mail.user.isBlank()) {
            showSettingsDialog()
            return
        }
        Toast.makeText(this, R.string.fetching, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val cfg = App.instance.configLoader.reload()
            val result = runCatching {
                val items = fetcher.fetch(cfg)
                App.instance.library.merge(items)
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

    /** 设置对话框: 输入邮箱账号与授权码, 保存后刷新片库。 */
    private fun showSettingsDialog() {
        val cfg = App.instance.configLoader.config
        val dp = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), 0)
        }
        fun label(text: String) = TextView(this@ListActivity).apply {
            this.text = text
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
        }
        val etUser = EditText(this).apply {
            hint = "example@163.com"
            setSingleLine(true)
            setText(cfg.mail.user)
        }
        val etAuth = EditText(this).apply {
            hint = "16 位授权码"
            setSingleLine(true)
            setText(cfg.mail.authCode)
        }
        layout.addView(label(getString(R.string.settings_user_hint)))
        layout.addView(etUser)
        layout.addView(label(getString(R.string.settings_auth_hint)))
        layout.addView(etAuth)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val user = etUser.text.toString().trim()
                val auth = etAuth.text.toString().trim()
                if (user.isEmpty() || auth.isEmpty()) {
                    Toast.makeText(this, R.string.setup_missing, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                App.instance.configLoader.save(
                    cfg.copy(mail = cfg.mail.copy(user = user, authCode = auth))
                )
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                doRefresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(item: VideoItem) {
        val dp = resources.displayMetrics.density
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), 0)
            addView(TextView(this@ListActivity).apply {
                text = getString(R.string.confirm_ok) + "\n" + item.title +
                    if (item.episode > 0) " E${item.episode}" else ""
            })
            addView(CheckBox(this@ListActivity).apply {
                text = getString(R.string.dl_del_also)
                isChecked = true
                setPadding(0, (12 * dp).toInt(), 0, 0)
            })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete)
            .setView(wrap)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val alsoFiles = wrap.getChildAt(1) as CheckBox
                lifecycleScope.launch {
                    App.instance.library.delete(item.id)
                    App.instance.progress.remove(ProgressStore.keyOf(item.title, item.episode))
                    if (alsoFiles.isChecked) {
                        MovieFiles.deleteLocalFiles(this@ListActivity, item.displayId)
                    }
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
            val headers = item.headers
            putExtra(PlayerActivity.EXTRA_HEADER_KEYS, headers.keys.toTypedArray())
            putExtra(PlayerActivity.EXTRA_HEADER_VALS, headers.values.toTypedArray())
        }
        startActivity(intent)
    }

    /** "先下后播": 已下载直接播放, 否则弹进度对话框下载 → 拼接 MP4(编号.ts) → 播放本地文件。 */
    private fun downloadThenPlay(item: VideoItem) {
        MovieFiles.localFileFor(this, item)?.let {
            Toast.makeText(this, R.string.dl_exists, Toast.LENGTH_SHORT).show()
            playLocal(it, item)
            return
        }

        val dir = MovieFiles.dir(this)
        val dp = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), 0)
        }
        val tvDetail = TextView(this).apply {
            text = item.title + if (item.episode > 0) " E${item.episode}" else ""
        }
        val tvStatus = TextView(this).apply {
            text = getString(R.string.dl_stage_parse)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
        }
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        layout.addView(tvDetail)
        layout.addView(tvStatus)
        layout.addView(pb)

        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.dl_title)
            .setView(layout)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dlg.setOnDismissListener { downloader?.cancel() }
        dlg.show()

        downloader = M3u8Downloader(
            m3u8Url = item.url,
            headers = item.headers,
            workDir = File(dir, "${item.displayId}_tmp"),
            outFile = File(dir, "${item.displayId}.ts"),
            listener = object : M3u8Downloader.Listener {
                override fun onProgress(stage: String, percent: Int, indeterminate: Boolean) =
                    runOnUiThread {
                        tvStatus.text = stage
                        pb.isIndeterminate = indeterminate
                        if (!indeterminate) pb.progress = percent
                    }

                override fun onDone(file: File) = runOnUiThread {
                    dlg.dismiss()
                    adapter.setDownloaded(MovieFiles.downloadedIds(this@ListActivity))
                    playLocal(file, item)
                }

                override fun onError(message: String) = runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(this@ListActivity,
                        getString(R.string.dl_failed, message), Toast.LENGTH_LONG).show()
                }
            }
        )
        downloader?.start()
    }

    /** 播放已下载的本地文件 (ts), 失败自动切在线。 */
    private fun playLocal(file: File, item: VideoItem) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, file.absolutePath)
            putExtra(PlayerActivity.EXTRA_FALLBACK_URL, item.url)
            putExtra(PlayerActivity.EXTRA_TITLE,
                item.title + if (item.episode > 0) " E${item.episode}" else "")
            putExtra(PlayerActivity.EXTRA_HEADER_KEYS, item.headers.keys.toTypedArray())
            putExtra(PlayerActivity.EXTRA_HEADER_VALS, item.headers.values.toTypedArray())
        }
        startActivity(intent)
    }
}
