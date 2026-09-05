package com.tv.mailvod.net

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
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

    /** 检查 Gitee 更新; 有新版下载并弹安装窗。manual=true 绕过节流并给结果反馈。 */
    fun check(manual: Boolean) {
        activity.getSharedPreferences("update", Context.MODE_PRIVATE).edit()
            .putLong("last_check", System.currentTimeMillis()).apply()
        if (manual) Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val checker = UpdateChecker(activity.applicationContext)
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
            val apk = withContext(Dispatchers.IO) { checker.downloadUpdate(info) }
            if (apk == null) {
                if (manual) Toast.makeText(activity, R.string.update_download_fail,
                    Toast.LENGTH_LONG).show()
                return@launch
            }
            promptInstall(apk)
        }
    }

    /** 是否距上次检查超过 30 分钟(自动检查节流用)。 */
    fun shouldAutoCheck(): Boolean = System.currentTimeMillis() -
        activity.getSharedPreferences("update", Context.MODE_PRIVATE)
            .getLong("last_check", 0) > 30 * 60 * 1000L

    /** 弹"发现新版本"对话框, 确认后调系统安装器。 */
    private fun promptInstall(apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_found)
            .setMessage(R.string.update_msg)
            .setPositiveButton(R.string.update_install) { _, _ ->
                runCatching {
                    val intent = Intent(Intent.ACTION_INSTALL_PACKAGE, uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    activity.startActivity(intent)
                }.onFailure { Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
