package com.tv.mailvod.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.tv.mailvod.App
import com.tv.mailvod.R
import com.tv.mailvod.databinding.ActivitySettingsBinding

/**
 * 设置页: 输入 APK 更新地址与片源地址(默认值预填), 保存后写入 config.json。
 * 返回片库页后其 onResume 会自动重载列表(配置改动即时生效)。
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.instance.configLoader.ensureLoaded()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val cfg = App.instance.configLoader.config
        binding.etUpdateUrl.setText(cfg.updateUrl)
        binding.etUrl.setText(cfg.libraryUrl)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnConfirm.setOnClickListener { save() }
        // 默认焦点: 保存按钮; 输入时按上键到输入框唤起系统键盘
        binding.btnConfirm.requestFocus()
    }

    /** 校验非空后写 config.json; 成功 Toast 提示并关闭页面。 */
    private fun save() {
        val updateUrl = binding.etUpdateUrl.text.toString().trim()
        val libUrl = binding.etUrl.text.toString().trim()
        if (updateUrl.isEmpty() || libUrl.isEmpty()) {
            Toast.makeText(this, R.string.settings_url_missing, Toast.LENGTH_LONG).show()
            return
        }
        val cfg = App.instance.configLoader.config
        App.instance.configLoader.save(cfg.copy(updateUrl = updateUrl, libraryUrl = libUrl))
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
