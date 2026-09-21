package com.tv.mailvod.net

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.tv.mailvod.App
import com.tv.mailvod.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 应用内更新流程(TV 与 phone 共用): 30 分钟节流的自动检查 + 手动检查,
 * 远端版本更大时下载 APK 并弹安装窗(FileProvider 暴露 cacheDir/update.apk)。
 * channel 决定读 version.json 的哪一段(tv=顶层 / phone=phone 子对象)。
 */
class AppUpdater(
    private val activity: ComponentActivity,
    private val channel: String
) {

    companion object {
        private const val TAG = "AppUpdater"

        /** 进程级下载去重: 片库页自动检查与关于页手动检查同时进行时只跑一次下载,
         *  避免两个协程并发写同一 cacheDir/update.apk 造成文件交错损坏。 */
        @Volatile
        var downloading = false
    }

    /** 检查 Gitee 更新; 有新版下载并弹安装窗。manual=true 绕过节流并给结果反馈。 */
    fun check(manual: Boolean) {
        activity.getSharedPreferences("update", Context.MODE_PRIVATE).edit()
            .putLong("last_check", System.currentTimeMillis()).apply()
        if (manual) Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            // 每次检查现读 config(设置页可改 update_url), 无需构造时传入
            val checker = UpdateChecker(activity.applicationContext,
                App.instance.configLoader.config.updateUrl)
            val info = withContext(Dispatchers.IO) { checker.fetchRemoteVersion(channel) }
            if (info == null) {
                if (manual) Toast.makeText(activity, R.string.update_check_fail,
                    Toast.LENGTH_LONG).show()
                return@launch
            }
            val local = activity.runCatching {
                activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode
            }.getOrDefault(0)
            if (info.versionCode <= local) {
                if (manual) Toast.makeText(activity,
                    activity.getString(R.string.update_latest, info.versionName),
                    Toast.LENGTH_LONG).show()
                return@launch
            }
            if (downloading) {
                // 已有下载在途: 直接复用其结果(完成后它会弹安装窗), 不再并发下载
                if (manual) Toast.makeText(activity, R.string.update_downloading,
                    Toast.LENGTH_SHORT).show()
                return@launch
            }
            downloading = true
            try {
                val apk = withContext(Dispatchers.IO) { checker.downloadUpdate(info) }
                if (apk == null) {
                    if (manual) Toast.makeText(activity, R.string.update_download_fail,
                        Toast.LENGTH_LONG).show()
                    return@launch
                }
                promptInstall(apk)
            } finally {
                downloading = false
            }
        }
    }

    /** 是否距上次检查超过 30 分钟(自动检查节流用)。 */
    fun shouldAutoCheck(): Boolean = System.currentTimeMillis() -
        activity.getSharedPreferences("update", Context.MODE_PRIVATE)
            .getLong("last_check", 0) > 30 * 60 * 1000L

    /** 弹"发现新版本"对话框, 确认后调系统安装器。 */
    private fun promptInstall(apk: File) {
        // 防损坏文件静默无反应: APK 头无法解析(下载中断/写坏)时删掉提示重试, 下次检查会重新下载
        if (activity.packageManager.getPackageArchiveInfo(apk.path, 0) == null) {
            Log.w(TAG, "update.apk unparsable, drop it")
            apk.delete()
            Toast.makeText(activity, R.string.update_download_fail, Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val dlg = AlertDialog.Builder(activity)
            .setTitle(R.string.update_found)
            .setMessage(R.string.update_msg)
            .setPositiveButton(R.string.update_install) { _, _ ->
                runCatching {
                    activity.startActivity(installIntent(uri))
                }.onFailure { Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // TV 遥控器偶发坑: 系统弹窗 show() 后无按钮获得焦点 → OK 键落空表现为"点了没反应"
        // (0.8.8 弹窗按钮同源问题), onShow 回调里强制把焦点交给"立即安装"按钮
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
        }
        dlg.show()
    }

    /**
     * 安装 Intent: 首选 ACTION_INSTALL_PACKAGE(现代系统标准方式);
     * 部分精简 ROM(小米电视 6.0.1)没有响应它的安装器, 会抛
     * "No Activity found to handle Intent" — 此时回退 ACTION_VIEW + APK mime
     * (老式系统安装器/自带安装程序响应的是这个)。
     */
    private fun installIntent(uri: android.net.Uri): Intent {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        val install = Intent(Intent.ACTION_INSTALL_PACKAGE, uri).addFlags(flags)
        if (activity.packageManager.queryIntentActivities(install, 0).isNotEmpty()) {
            return install
        }
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(flags)
    }
}
