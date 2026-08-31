package com.tv.mailvod.ui

import android.os.Bundle
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

/**
 * 全屏播放页。ExoPlayer 2.x 加载 HLS(m3u8)，注入防盗链 headers。
 *
 * 遥控器：OK 暂停/播放，左/右 ±10s（PlayerView 默认 controller），返回退出。
 */
class PlayerActivity : ComponentActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null

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
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""

        play(url, headers, title)
    }

    private fun play(url: String, headers: Map<String, String>, title: String) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("MailVod")
            .setDefaultRequestProperties(headers)

        val mediaItem = MediaItem.Builder().setUri(url).build()
        val mediaSource: MediaSource = HlsMediaSource.Factory(httpFactory)
            .createMediaSource(mediaItem)

        player = ExoPlayer.Builder(this).build().also { p ->
            p.setMediaSource(mediaSource)
            p.prepare()
            p.playWhenReady = true
        }
        binding.playerView.player = player
        binding.playerView.keepScreenOn = true

        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity,
                    getString(R.string.play_error, error.message ?: ""), Toast.LENGTH_LONG).show()
                finish()
            }
        })
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

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_HEADER_KEYS = "extra_header_keys"
        const val EXTRA_HEADER_VALS = "extra_header_vals"
    }
}
