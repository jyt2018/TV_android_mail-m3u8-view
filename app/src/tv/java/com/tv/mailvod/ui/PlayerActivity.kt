package com.tv.mailvod.ui

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.tv.mailvod.R
import com.tv.mailvod.databinding.ActivityPlayerBinding
import com.tv.mailvod.playback.VodPlayer

/**
 * 全屏播放页（TV 壳）。播放核心在共用 VodPlayer(HLS/headers/续播/兜底)。
 *
 * 遥控器：OK 暂停/播放，左/右 ±10s（PlayerView 默认 controller），返回退出。
 */
class PlayerActivity : ComponentActivity() {

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

    /** 遥控器按键定制: OK=暂停/继续, 左右=快退/快进 10s。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val p = vod.player ?: return super.onKeyDown(keyCode, event)
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
