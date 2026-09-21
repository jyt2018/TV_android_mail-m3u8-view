package com.tv.mailvod.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.tv.mailvod.R
import com.tv.mailvod.databinding.ActivityPlayerBinding
import com.tv.mailvod.playback.VodPlayer

/**
 * 全屏播放页（TV 壳）。播放核心在共用 VodPlayer(HLS/headers/续播/兜底)。
 *
 * 自建控制条（ExoPlayer 默认控制条在老电视渲染不出, v0.8.2 弃用）:
 * - OK = 播放/暂停切换, 左/右 = ±10s, 上/下 = 仅弹控制条
 * - 控制条弹出时页眉同步显示片名+年份+国家小字 (v0.8.4)
 * - 隐藏规则: playWhenReady=true(播放中/缓冲中) 5 秒后隐藏; 用户暂停时经常驻
 *   (v0.8.4 修: 旧逻辑用 isPlaying 判断, seek 后缓冲期 isPlaying=false 导致不隐藏)
 */
class PlayerActivity : ComponentActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var vod: VodPlayer
    private var lastBackAt = 0L
    private var title: String = ""
    private var meta: String = ""

    private val uiHandler = Handler(Looper.getMainLooper())

    /** 每 500ms 刷新控制条 (按钮图标/进度/时间), GONE 时也刷新(开销可忽略)。 */
    private val tick = object : Runnable {
        override fun run() {
            updateBar()
            uiHandler.postDelayed(this, 500)
        }
    }

    /** 播放中 5 秒无按键自动隐藏控制条与页眉小字; 用户暂停时经常驻。 */
    private val hideBar = Runnable {
        binding.controlBar.visibility = View.GONE
        binding.tvMeta.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra(EXTRA_URL) ?: run {
            finish(); return
        }
        val keys = intent.getStringArrayExtra(EXTRA_HEADER_KEYS) ?: arrayOf()
        val vals = intent.getStringArrayExtra(EXTRA_HEADER_VALS) ?: arrayOf()
        val headers = keys.zip(vals).toMap()
        val fallbackUrl = intent.getStringExtra(EXTRA_FALLBACK_URL)
        title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        meta = intent.getStringExtra(EXTRA_META) ?: title

        vod = VodPlayer(this, binding.playerView) { msg ->
            Toast.makeText(this, getString(R.string.play_error, msg), Toast.LENGTH_LONG).show()
            finish()
        }
        vod.start(url, title, headers, fallbackUrl)

        binding.btnToggle.setOnClickListener { togglePlay() }
        binding.seekBar.max = 1000
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) vod.player?.let { p ->
                    if (p.duration > 0) p.seekTo(p.duration * progress / 1000)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        uiHandler.post(tick)
        showBar() // 进页先亮 5s, 让用户知道有控制条
    }

    override fun onStart() {
        super.onStart()
        vod.resume()
    }

    override fun onStop() {
        super.onStop()
        vod.suspendPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        vod.release()
    }

    /** 遥控器按键定制: OK=播放/暂停切换, 左右=快退/快进 10s, 任意方向键弹控制条。在分发层拦截, 不受焦点影响。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = vod.player
        if (p != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.repeatCount == 0) togglePlay()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
                    showBar()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    p.seekTo(p.currentPosition + 10_000)
                    showBar()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    showBar()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 播放/暂停切换。暂停后控制条常驻, 恢复播放 5s 后自动隐藏。 */
    private fun togglePlay() {
        val p = vod.player ?: return
        if (p.isPlaying) p.pause() else p.play()
        showBar()
    }

    /**
     * 显示控制条 + 页眉小字; 5s 后自动隐藏 (用户暂停时经常驻)。
     * 用 playWhenReady 而非 isPlaying: seek 后的缓冲期 isPlaying=false 但用户并未暂停,
     * 旧逻辑此时不排隐藏定时器 → 控制条永远不消失 (v0.8.4 修复)。
     */
    private fun showBar() {
        binding.controlBar.visibility = View.VISIBLE
        binding.tvMeta.visibility = View.VISIBLE
        updateBar()
        uiHandler.removeCallbacks(hideBar)
        if (vod.player?.playWhenReady == true) uiHandler.postDelayed(hideBar, 5_000)
    }

    /** 同步控制条状态: 页眉小字/按钮图标/时间/进度 (tick 每秒调用)。 */
    private fun updateBar() {
        val p = vod.player ?: return
        binding.tvMeta.text = metaWithDuration()
        val playing = p.isPlaying
        binding.btnToggle.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.btnToggle.contentDescription = getString(
            if (playing) R.string.player_pause else R.string.player_play
        )
        val dur = p.duration
        if (dur > 0) {
            // 右下角: 当前时间 / 剩余时间 (时:分, 无秒), 总时长在左上角片名后
            binding.tvTime.text =
                fmtClock(p.currentPosition) + " / " + fmtClock(dur - p.currentPosition)
            binding.seekBar.progress = (p.currentPosition * 1000L / dur).toInt()
        } else {
            binding.tvTime.text = getString(R.string.player_live)
            binding.seekBar.progress = 0
        }
    }

    /** 页眉小字: 片名 (年份/国家) 时长:h:mm — meta 已含片名与年份/国家, 播放器就绪后追加时长。 */
    private fun metaWithDuration(): String {
        val dur = vod.player?.duration ?: 0L
        if (dur <= 0) return meta
        val s = dur / 1000
        // 时长只到分钟: >=1h 显示 h:mm (如 1:23), 不足 1 小时显示 mm:ss (如 45:20)
        val durationText = if (s >= 3600) "%d:%02d".format(s / 3600, s % 3600 / 60)
        else "%d:%02d".format(s / 60, s % 60)
        return meta + " 时长:" + durationText
    }

    /** ms → 时:分 (无秒): 0:05 / 1:23。 */
    private fun fmtClock(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 3600, s % 3600 / 60)
    }

    /** 返回键防误触: 第一次提示, 2 秒内再按一次才退出(进度照常落盘)。 */
    override fun onBackPressed() {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 2000) {
            finish()
        } else {
            lastBackAt = now
            Toast.makeText(this, R.string.back_again, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_FALLBACK_URL = "extra_fallback_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_META = "extra_meta"
        const val EXTRA_HEADER_KEYS = "extra_header_keys"
        const val EXTRA_HEADER_VALS = "extra_header_vals"
    }
}
