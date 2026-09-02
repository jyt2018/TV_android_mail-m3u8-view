package com.tv.mailvod.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tv.mailvod.App
import com.tv.mailvod.R
import com.tv.mailvod.databinding.ActivityListBinding
import com.tv.mailvod.download.M3u8Downloader
import com.tv.mailvod.mail.MailFetcher
import com.tv.mailvod.store.VideoItem
import kotlinx.coroutines.launch
import java.io.File

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
    private var downloader: M3u8Downloader? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvTitle.text = getString(R.string.app_name)
        val ver = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("?")
        binding.tvVersion.text = "v $ver"

        val cfg = App.instance.configLoader.config
        adapter = VideoAdapter(
            config = cfg,
            onPlay = { item -> startPlayer(item) },
            onDownloadPlay = { item -> downloadThenPlay(item) },
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
        // (rowRoot 不可聚焦, 焦点落在行内按钮上, 需沿 parent 链判断是否 RV 后代)
        binding.rvList.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            val rv = binding.rvList
            val inRv = if (newFocus == null) false else run {
                var p = newFocus.parent
                while (p != null && p !== rv) p = p.parent
                p === rv
            }
            if (!inRv) {
                adapter.setHighlight(rv, -1)
            }
        }

        binding.btnRefresh.setOnClickListener { doRefresh() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.btnSearch.setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }
        binding.ivIcon.setOnClickListener { showAboutDialog() }

        // 启动后自动刷新一次 (仅 onCreate, 从播放页返回的 onResume 不重复拉取)
        if (App.instance.configLoader.config.mail.user.isNotBlank()) doRefresh()
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
            adapter.setDownloaded(downloadedIds())
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

    /** 关于弹窗: 版本、开发者、操作使用说明。 */
    private fun showAboutDialog() {
        val info = packageManager.getPackageInfo(packageName, 0)
        val message = getString(R.string.about_developer) +
            "\n版本: v " + info.versionName + " (" + info.versionCode + ")" +
            "\n\n" + getString(R.string.about_usage)
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setIcon(R.drawable.ic_head)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** 设置弹窗: 输入邮箱账号与授权码, 确定后写入 config.json。 */
    private fun showSettingsDialog() {
        val cfg = App.instance.configLoader.config
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etUser = view.findViewById<android.widget.EditText>(R.id.etUser)
        val etAuth = view.findViewById<android.widget.EditText>(R.id.etAuth)
        etUser.setText(cfg.mail.user)
        etAuth.setText(cfg.mail.authCode)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val user = etUser.text.toString().trim()
                val auth = etAuth.text.toString().trim()
                App.instance.configLoader.save(
                    cfg.copy(mail = cfg.mail.copy(user = user, authCode = auth))
                )
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
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
                text = getString(R.string.confirm_ok) + "\n\n" + item.title +
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
                    if (alsoFiles.isChecked) deleteLocalFiles(item.displayId)
                    loadList()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 删除条目对应的本地文件 (编号.ts/mp4 + 临时分片目录)。 */
    private fun deleteLocalFiles(displayId: String) {
        val dir = moviesDir()
        dir.listFiles { f -> f.isFile && f.nameWithoutExtension == displayId }
            ?.forEach { it.delete() }
        File(dir, "${displayId}_tmp").deleteRecursively()
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

    /** "先下后播": 已下载直接播放, 否则弹进度窗下载 → 合并 MP4(编号.mp4) → 播放本地文件。 */
    private fun downloadThenPlay(item: VideoItem) {
        localFileFor(item)?.let {
            Toast.makeText(this, R.string.dl_exists, Toast.LENGTH_SHORT).show()
            playLocal(it, item)
            return
        }

        val dir = moviesDir()
        val view = layoutInflater.inflate(R.layout.dialog_download, null)
        val tvStatus = view.findViewById<TextView>(R.id.tvStatus)
        val tvDetail = view.findViewById<TextView>(R.id.tvDetail)
        val pb = view.findViewById<ProgressBar>(R.id.pbDownload)
        tvStatus.text = getString(R.string.dl_stage_parse)
        tvDetail.text = item.title + if (item.episode > 0) " E${item.episode}" else ""
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.dl_title)
            .setView(view)
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
                    adapter.setDownloaded(downloadedIds())
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

    /** 本地影片目录 (app 外部私有目录 movies/)。 */
    private fun moviesDir(): File =
        File(getExternalFilesDir(null), "movies").apply { mkdirs() }

    /** 已下载条目 (编号.ts 拼接产物) 的 displayId 集合。 */
    private fun downloadedIds(): Set<String> =
        moviesDir().listFiles { f -> f.isFile && f.extension == "ts" }
            ?.mapTo(HashSet()) { it.nameWithoutExtension } ?: emptySet()

    /** 条目对应的本地播放文件 (编号.ts), 无则 null。 */
    private fun localFileFor(item: VideoItem): File? {
        val ts = File(moviesDir(), "${item.displayId}.ts")
        return if (ts.exists() && ts.length() > 100 * 1024) ts else null
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
