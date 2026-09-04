package com.tv.mailvod.ui

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.tv.mailvod.App
import com.tv.mailvod.R
import com.tv.mailvod.databinding.ActivityPlayerBinding
import java.io.File

/**
 * 全屏播放页。ExoPlayer 2.x 加载 HLS(m3u8)，注入防盗链 headers。
 *
 * 遥控器：OK 暂停/播放，左/右 ±10s（PlayerView 默认 controller），返回退出。
 */
class PlayerActivity : ComponentActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var isLocal = false
    private var fallbackUrl: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var fallbackUsed = false
    private lateinit var progressKey: String

    // 继续播放: 内存实时进度 + 每10s落盘 + onPause/onDestroy 必存
    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveRunnable = object : Runnable {
        override fun run() {
            saveProgress()
            saveHandler.postDelayed(this, 10_000)
        }
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
        headers = keys.zip(vals).toMap()
        fallbackUrl = intent.getStringExtra(EXTRA_FALLBACK_URL)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""

        play(url, title)
    }

    private fun play(url: String, title: String) {
        progressKey = title
        isLocal = url.startsWith("/") || url.startsWith("file:")
        val uri = if (isLocal && !url.startsWith("file:")) Uri.fromFile(File(url)) else Uri.parse(url)
        val mediaItem = MediaItem.Builder().setUri(uri).build()

        player = ExoPlayer.Builder(this).build()
        // 继续播放: 有未完成进度则先定位再 prepare
        val saved = App.instance.progress.get(progressKey)
        if (saved != null && saved.positionMs > 0) {
            player?.seekTo(saved.positionMs)
            Toast.makeText(this, getString(R.string.resume_from, fmtTime(saved.positionMs)),
                Toast.LENGTH_SHORT).show()
        }
        if (isLocal) {
            // 本地文件 (mp4/ts), 让 ExoPlayer 按容器自动推断媒体源
            player?.setMediaItem(mediaItem)
        } else {
            attachHlsSource(uri)
        }
        player?.prepare()
        player?.playWhenReady = true
        binding.playerView.player = player
        binding.playerView.keepScreenOn = true
        saveHandler.post(saveRunnable)

        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // 本地文件损坏/不可播时自动切换在线源 (兜底)
                if (isLocal && !fallbackUsed && fallbackUrl != null) {
                    fallbackUsed = true
                    Toast.makeText(this@PlayerActivity,
                        R.string.dl_switch_online, Toast.LENGTH_SHORT).show()
                    player?.setMediaSource(hlsSourceFor(Uri.parse(fallbackUrl)))
                    player?.prepare()
                    player?.playWhenReady = true
                    return
                }
                Toast.makeText(this@PlayerActivity,
                    getString(R.string.play_error, error.message ?: ""), Toast.LENGTH_LONG).show()
                finish()
            }
        })
    }

    /** 节流落盘; 看完(>=98% 或剩<30s)自动清除进度。 */
    private fun saveProgress() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        if (pos <= 0) return
        App.instance.progress.save(progressKey, pos, dur)
    }

    /** ms → h:mm:ss / mm:ss。 */
    private fun fmtTime(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    private fun hlsSourceFor(uri: Uri): MediaSource =
        HlsMediaSource.Factory(
            DefaultHttpDataSource.Factory()
                .setUserAgent("MailVod")
                .setDefaultRequestProperties(headers)
        ).createMediaSource(MediaItem.Builder().setUri(uri).build())

    private fun attachHlsSource(uri: Uri) {
        player?.setMediaSource(hlsSourceFor(uri))
    }

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        saveProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveHandler.removeCallbacks(saveRunnable)
        saveProgress()
        player?.release()
        player = null
    }

    /** 遥控器按键定制: OK=暂停/继续, 左右=快退/快进 10s。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val p = player ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (p.isPlaying) {
                    p.pause()
                    binding.playerView.showController() // 暂停时弹出控制条(有进度条反馈)
                } else {
                    p.play()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                p.seekTo(p.currentPosition + 10_000)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_FALLBACK_URL = "extra_fallback_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_HEADER_KEYS = "extra_header_keys"
        const val EXTRA_HEADER_VALS = "extra_header_vals"
    }
}
