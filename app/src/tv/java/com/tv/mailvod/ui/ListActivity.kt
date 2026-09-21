package com.tv.mailvod.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tv.mailvod.App
import com.tv.mailvod.R
import com.tv.mailvod.databinding.ActivityListBinding
import com.tv.mailvod.download.M3u8Downloader
import com.tv.mailvod.download.MovieFiles
import com.tv.mailvod.net.LibrarySync
import com.tv.mailvod.store.ProgressStore
import com.tv.mailvod.store.VideoItem
import kotlinx.coroutines.launch
import java.io.File

/**
 * 列表页（唯一主页面, 片库页）。
 * - onResume 加载 library.json 并显示
 * - 刷新键：Gitee 片库地址拉取 → 合并 → 刷新列表
 * - 行播放键：跳转 PlayerActivity（传 url + headers）
 * - 行删除键：系统 AlertDialog 二次确认 → 删除并刷新
 *
 * 遥控器焦点：行根(rowRoot)可聚焦, 上下键在行间移动并定向默认按钮
 * (已下载行→本地播放, 未下载行→在线播放)；左右键行内走按钮。
 */
class ListActivity : ComponentActivity() {

    companion object {
        /** 刷新页刷新成功后置位; 片库页 onResume 消费: 列表滚动到顶部。 */
        var pendingScrollTop = false
    }

    private lateinit var binding: ActivityListBinding
    private lateinit var adapter: VideoAdapter
    private val sync = LibrarySync()
    private var downloader: M3u8Downloader? = null
    // 播放页返回时定位到的行位置(刚才播放的片), onResume 消费后复位 -1
    private var lastPlayedPos = -1
    private val updater = com.tv.mailvod.net.AppUpdater(this, com.tv.mailvod.net.UpdateChecker.CHANNEL_TV)

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
            "title" to "片名",
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

        // 全局焦点监听:
        // 1) 焦点跳出 RecyclerView → 清除所有行高亮
        // 2) 焦点落在行根(rowRoot 可聚焦) → 默认按钮定向: 已下载行落"本地播放", 未下载落"在线播放"
        //    (从本行按钮移出来的则不重定向, 停在行根, 让用户下一次左右键继续走行内按钮)
        binding.rvList.viewTreeObserver.addOnGlobalFocusChangeListener { oldFocus, newFocus ->
            val rv = binding.rvList
            val inRv = if (newFocus == null) false else run {
                var p = newFocus.parent
                while (p != null && p !== rv) p = p.parent
                p === rv
            }
            if (!inRv) {
                adapter.setHighlight(rv, -1)
                return@addOnGlobalFocusChangeListener
            }
            if (newFocus.id == R.id.rowRoot) {
                val pos = rv.getChildAdapterPosition(newFocus)
                if (pos == RecyclerView.NO_POSITION) {
                    return@addOnGlobalFocusChangeListener
                }
                if (oldFocus != null && oldFocus.parent === newFocus) {
                    adapter.setHighlight(rv, pos)
                } else {
                    adapter.focusPreferred(rv, pos)
                }
            }
        }

        binding.btnRefresh.setOnClickListener { startActivity(Intent(this, RefreshActivity::class.java)) }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.ivIcon.setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }

        // 启动后自动刷新一次 (仅 onCreate, 从播放页返回的 onResume 不重复拉取)
        doRefresh()
    }

    /** 弹"发现新版本"对话框逻辑在共用 AppUpdater; 此处仅保留调用入口。 */

    override fun onResume() {
        super.onResume()
        // 刷新页刷新成功后置位 pendingScrollTop, 返回片库页时列表滚到顶部(消费后复位)
        val top = pendingScrollTop
        pendingScrollTop = false
        // 播放页返回时焦点定位到刚才播放的行(消费后复位); 删除行回填走 confirmDelete 的 loadList(pos)
        val played = lastPlayedPos
        lastPlayedPos = -1
        loadList(focusPos = played, scrollTop = top)
        // 自动检查更新(TV 上按返回退出进程常驻, onCreate 不再重跑 → 挪到 onResume + 30 分钟节流)
        if (updater.shouldAutoCheck()) updater.check(manual = false)
    }

    /** 遥控器按键定制: 上下键强制行间路由(已下载行默认落"本地播放"), 不依赖系统焦点搜索。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> if (moveRowFocus(+1)) return true
                KeyEvent.KEYCODE_DPAD_UP -> if (moveRowFocus(-1)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 上下键移动行焦点: 计算当前行 ±1, 滚动到位后把焦点交给该行默认按钮 (已下载 → 本地播放)。
     * 返回 false 表示不拦截 (焦点不在列表内 / 首行再向上 → 交给默认焦点引擎去表头)。
     * 焦点不在 rvList 子树时 parent 链会走到 DecorView → ViewRootImpl(非 View), 必须在强转前判型返回,
     * 否则 ClassCastException 闪退 (0.8.7 修复: 焦点在标题栏按钮上按上下键即崩)。
     */
    private fun moveRowFocus(dir: Int): Boolean {
        val rv = binding.rvList
        val focused = currentFocus ?: return false
        var child = focused
        var p: android.view.ViewParent? = child.parent
        while (p != null && p !== rv) {
            if (p !is View) return false
            child = p
            p = child.parent
        }
        if (p !== rv) return false
        val pos = rv.getChildAdapterPosition(child)
        if (pos == RecyclerView.NO_POSITION) return false
        val target = pos + dir
        if (target < 0) return false
        if (target >= adapter.itemCount) return true
        rv.scrollToPosition(target)
        rv.post { adapter.focusPreferred(rv, target) }
        return true
    }

    /** 遥控器菜单键(KEYCODE_MENU=82) = 刷新。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            doRefresh()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun loadList(focusPos: Int = -1, scrollTop: Boolean = false) {
        lifecycleScope.launch {
            val list = App.instance.library.load()
            adapter.submit(list)
            adapter.setDownloaded(downloadedIds())
            binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            binding.rvList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            updateTitle(list.size)
            // 焦点回填: focusPos >= 0 表示刚删除了一行, 该位置由下一行顶上, 焦点留在原地;
            // 删光后列表为空, 焦点给刷新按钮, 遥控器不落空。
            if (list.isEmpty()) {
                binding.btnRefresh.requestFocus()
            } else if (focusPos >= 0) {
                val target = focusPos.coerceAtMost(list.size - 1)
                binding.rvList.scrollToPosition(target)
                binding.rvList.post { adapter.focusPreferred(binding.rvList, target) }
            } else if (scrollTop) {
                // 刷新后新片置顶, 列表滚回顶部
                binding.rvList.scrollToPosition(0)
            }
        }
    }

    private fun updateTitle(count: Int) {
        val base = getString(R.string.app_name)
        binding.tvTitle.text = if (count > 0) "$base (共$count)" else base
    }

    /** 拉取 Gitee 片库并合并到 library.json。遥控器菜单键快捷刷新; "刷新"按钮进刷新页(带 log)。 */
    private fun doRefresh() {
        Toast.makeText(this, R.string.fetching, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val cfg = App.instance.configLoader.reload()
            val result = runCatching {
                val items = sync.fetch(cfg.libraryUrl)
                val added = App.instance.library.merge(items)
                added
            }
            result.onSuccess { added ->
                Toast.makeText(this@ListActivity,
                    getString(R.string.fetch_done, added), Toast.LENGTH_SHORT).show()
                loadList(scrollTop = true)
            }.onFailure { e ->
                Toast.makeText(this@ListActivity,
                    getString(R.string.fetch_fail, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDelete(item: VideoItem) {
        val dp = resources.displayMetrics.density
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), 0)
            addView(TextView(this@ListActivity).apply {
                text = getString(R.string.confirm_ok) + "\n\n" + item.title
            })
        }
        // 复选框与文本拆成两个控件水平排列(方框垂直居中于文本行): CheckBox 自带文本时
        // 方框由框架拉伸定位, TV 深色主题下与文本错位
        val chk = CheckBox(this).apply { isChecked = true }
        wrap.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(chk)
            addView(TextView(this@ListActivity).apply {
                text = getString(R.string.dl_del_also)
                setPadding((8 * dp).toInt(), 0, 0, 0)
            })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * dp).toInt() })
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete)
            .setView(wrap)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                // 删除前记录行位置: 重载后焦点回填到原位置(下一行顶上), 见 loadList(focusPos)
                val pos = adapter.positionOf(item)
                lifecycleScope.launch {
                    App.instance.library.delete(item.title)
                    App.instance.progress.remove(ProgressStore.keyOf(item.title))
                    if (chk.isChecked) {
                        MovieFiles.deleteLocalFiles(this@ListActivity, item.title)
                    }
                    loadList(pos)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // 遥控器: 默认焦点落"删除"按钮。
        // 时机必须在 onShow 回调(按钮已 attach, requestFocus 才生效); show() 返回后立即请求无效 (0.8.8)。
        // 另: 弹窗内含可聚焦控件(复选框)时, 窗口获得焦点瞬间框架会把初始焦点塞给第一个可聚焦
        // view(复选框), 该时机晚于 onShow — 仅在 onShow 里 requestFocus 会被覆盖回复选框,
        // 必须 post 到消息队列之后执行才能稳赢。
        dlg.setOnShowListener {
            // 删除/取消按钮之间加间距(系统默认几乎贴在一起)
            val cancel = dlg.getButton(AlertDialog.BUTTON_NEGATIVE)
            (cancel.layoutParams as ViewGroup.MarginLayoutParams).marginEnd = (16 * dp).toInt()
            cancel.requestLayout()
            val del = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            del.post { del.requestFocus() }
        }
    }

    /** 删除条目对应的本地文件 (片名.ts/mp4 + 临时分片目录)。共用 MovieFiles。 */

    /** 页眉小字元信息: 片名(年份/国家), 缺项自动省略。 */
    private fun metaOf(item: VideoItem): String {
        val parts = listOfNotNull(
            item.year?.takeIf { it > 0 }?.toString(),
            item.country?.trim()?.takeIf { it.isNotEmpty() && it != "-" }
        )
        return if (parts.isEmpty()) item.title else "${item.title} (${parts.joinToString("/")})"
    }

    private fun startPlayer(item: VideoItem) {
        lastPlayedPos = adapter.positionOf(item)   // 返回片库页时定位到本行
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, item.url)
            putExtra(PlayerActivity.EXTRA_TITLE, item.title)
            putExtra(PlayerActivity.EXTRA_META, metaOf(item))
            // headers 用 String[] 传递（keys/values 平行）
            val headers = item.headers
            putExtra(PlayerActivity.EXTRA_HEADER_KEYS, headers.keys.toTypedArray())
            putExtra(PlayerActivity.EXTRA_HEADER_VALS, headers.values.toTypedArray())
        }
        startActivity(intent)
    }

    /** "先下后播": 已下载直接播放, 否则弹进度窗下载 → 拼接 TS(片名.ts) → 播放本地文件。 */
    private fun downloadThenPlay(item: VideoItem) {
        localFileFor(item)?.let {
            Toast.makeText(this, R.string.dl_exists, Toast.LENGTH_SHORT).show()
            playLocal(it, item)
            return
        }

        val dir = com.tv.mailvod.download.MovieFiles.dir(this)
        val view = layoutInflater.inflate(R.layout.dialog_download, null)
        val tvStatus = view.findViewById<TextView>(R.id.tvStatus)
        val tvDetail = view.findViewById<TextView>(R.id.tvDetail)
        val pb = view.findViewById<ProgressBar>(R.id.pbDownload)
        tvStatus.text = getString(R.string.dl_stage_parse)
        tvDetail.text = item.title
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
            workDir = MovieFiles.tmpDir(this, item),
            outFile = MovieFiles.outFile(this, item),
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

    /** 本地影片目录 (app 外部私有目录 movies/)。共用 MovieFiles。 */

    /** 已下载条目 (片名.ts 拼接产物) 的基名集合。 */
    private fun downloadedIds(): Set<String> =
        MovieFiles.downloadedKeys(this)

    /** 条目对应的本地播放文件 (片名.ts), 无则 null。 */
    private fun localFileFor(item: VideoItem): File? =
        com.tv.mailvod.download.MovieFiles.localFileFor(this, item)

    /** 播放已下载的本地文件 (ts), 失败自动切在线。 */
    private fun playLocal(file: File, item: VideoItem) {
        lastPlayedPos = adapter.positionOf(item)   // 返回片库页时定位到本行
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, file.absolutePath)
            putExtra(PlayerActivity.EXTRA_FALLBACK_URL, item.url)
            putExtra(PlayerActivity.EXTRA_TITLE, item.title)
            putExtra(PlayerActivity.EXTRA_META, metaOf(item))
            putExtra(PlayerActivity.EXTRA_HEADER_KEYS, item.headers.keys.toTypedArray())
            putExtra(PlayerActivity.EXTRA_HEADER_VALS, item.headers.values.toTypedArray())
        }
        startActivity(intent)
    }
}
