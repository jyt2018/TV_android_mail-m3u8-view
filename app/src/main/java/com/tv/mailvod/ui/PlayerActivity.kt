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
        isLocal = url.startsWith("/") || url.startsWith("file:")
        val uri = if (isLocal && !url.startsWith("file:")) Uri.fromFile(File(url)) else Uri.parse(url)
        val mediaItem = MediaItem.Builder().setUri(uri).build()

        player = ExoPlayer.Builder(this).build()
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
    }

    override fun onDestroy() {
        super.onDestroy()
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
