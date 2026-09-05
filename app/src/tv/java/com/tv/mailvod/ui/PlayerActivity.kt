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
 * 遥控器：OK 直接切换播放/暂停(不弹控制条)，左/右 ±10s，返回=2 秒内连按两次退出(防误触)。
 */
class PlayerActivity : ComponentActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var vod: VodPlayer
    private var lastBackAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // OK 键直接切换播放/暂停: 关闭 PlayerView 的自动弹出控制条
        // (默认在暂停/出错时会自动 show, 会抢走 OK 键且挡画面)
        binding.playerView.controllerAutoShow = false

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

    /** 遥控器按键定制: OK=播放/暂停切换(不弹控制条), 左右=快退/快进 10s。在分发层拦截, 不受焦点影响。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = vod.player
        if (p != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.repeatCount == 0) {
                        if (p.isPlaying) p.pause() else p.play()
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
        }
        return super.dispatchKeyEvent(event)
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
        const val EXTRA_HEADER_KEYS = "extra_header_keys"
        const val EXTRA_HEADER_VALS = "extra_header_vals"
    }
}
