package com.tv.mailvod.playback

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.ui.PlayerView
import com.tv.mailvod.App
import com.tv.mailvod.R
import java.io.File

/**
 * 播放器核心（TV 与 phone 共用）。
 * 封装: ExoPlayer 2.x HLS 加载、防盗链 headers 注入、断点续播(每10s落盘)、
 * 本地文件损坏自动切在线兜底。UI 壳(Activity)只负责按键/触控交互与生命周期转发。
 *
 * @param onFatal 播放错误(非兜底可救)回调, 在主线程, Activity 据此提示并退出。
 */
class VodPlayer(
    private val activity: Activity,
    private val playerView: PlayerView,
    private val onFatal: (String) -> Unit
) {
    var player: ExoPlayer? = null
        private set

    private var isLocal = false
    private var fallbackUrl: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var fallbackUsed = false
    private lateinit var progressKey: String

    // 继续播放: 每10s落盘 + onPause/onDestroy 必存
    private val saveHandler = Handler(Looper.getMainLooper())
    private val saveRunnable = object : Runnable {
        override fun run() {
            saveProgress()
            saveHandler.postDelayed(this, 10_000)
        }
    }

    /** 启动播放。url 为网络 m3u8 或本地文件路径。 */
    fun start(url: String, title: String, headers: Map<String, String>, fallbackUrl: String?) {
        this.headers = headers
        this.fallbackUrl = fallbackUrl
        progressKey = title
        isLocal = url.startsWith("/") || url.startsWith("file:")
        val uri = if (isLocal && !url.startsWith("file:")) Uri.fromFile(File(url)) else Uri.parse(url)
        val mediaItem = MediaItem.Builder().setUri(uri).build()

        player = ExoPlayer.Builder(activity).build()
        // 先设置媒体源: ExoPlayer 的 setMediaItem/setMediaSource 默认 resetPosition=true,
        // 若先 seekTo 再 setMediaSource, 断点位置会被丢弃(表现为提示续播却从 0 开始)
        if (isLocal) {
            // 本地文件 (mp4/ts), 让 ExoPlayer 按容器自动推断媒体源
            player?.setMediaItem(mediaItem)
        } else {
            player?.setMediaSource(hlsSourceFor(uri))
        }
        // 继续播放: prepare 之前的 seekTo 作为起始位置
        val saved = App.instance.progress.get(progressKey)
        if (saved != null && saved.positionMs > 0) {
            player?.seekTo(saved.positionMs)
            Log.i(TAG, "resume [$progressKey] seek to ${saved.positionMs}ms")
            Toast.makeText(activity, activity.getString(R.string.resume_from, fmtTime(saved.positionMs)),
                Toast.LENGTH_SHORT).show()
        }
        player?.prepare()
        player?.playWhenReady = true
        playerView.player = player
        playerView.keepScreenOn = true
        saveHandler.post(saveRunnable)

        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    Log.i(TAG, "ready [$progressKey] at ${player?.currentPosition}ms")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 本地文件损坏/不可播时自动切换在线源 (兜底)
                if (isLocal && !fallbackUsed && fallbackUrl != null) {
                    fallbackUsed = true
                    Toast.makeText(activity, R.string.dl_switch_online, Toast.LENGTH_SHORT).show()
                    player?.setMediaSource(hlsSourceFor(Uri.parse(fallbackUrl)))
                    player?.prepare()
                    player?.playWhenReady = true
                    return
                }
                onFatal(error.message ?: "")
            }
        })
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    /** 节流落盘; 看完(>=98% 或剩<30s)由 ProgressStore 内部清除。 */
    fun saveProgress() {
        val p = player ?: return
        val pos = p.currentPosition
        if (pos <= 0) return
        App.instance.progress.save(progressKey, pos, p.duration)
    }

    /** 生命周期转发: onStart 调用。 */
    fun resume() {
        player?.play()
    }

    /** 生命周期转发: onStop 调用。 */
    fun suspendPlayback() {
        player?.pause()
        saveProgress()
    }

    /** 生命周期转发: onDestroy 调用。 */
    fun release() {
        saveHandler.removeCallbacks(saveRunnable)
        saveProgress()
        player?.release()
        player = null
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

    companion object {
        private const val TAG = "VodPlayer"
    }
}
