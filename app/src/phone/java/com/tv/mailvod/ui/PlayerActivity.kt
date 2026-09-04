package com.tv.mailvod.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tv.mailvod.R
import com.tv.mailvod.databinding.ActivityPlayerBinding
import com.tv.mailvod.playback.VodPlayer

/**
 * 手机版全屏播放页（触屏壳）。播放核心在共用 VodPlayer(HLS/headers/续播/兜底)。
 * 触屏点按唤出/隐藏 ExoPlayer 默认控制条(进度/暂停/快进快退), 无需自定义按键。
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var vod: VodPlayer

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
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""

        vod = VodPlayer(this, binding.playerView) { msg ->
            Toast.makeText(this, getString(R.string.play_error, msg), Toast.LENGTH_LONG).show()
            finish()
        }
        vod.start(url, title, headers, fallbackUrl)
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
        vod.release()
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_FALLBACK_URL = "extra_fallback_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_HEADER_KEYS = "extra_header_keys"
        const val EXTRA_HEADER_VALS = "extra_header_vals"
    }
}
