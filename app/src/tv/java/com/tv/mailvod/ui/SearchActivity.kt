package com.tv.mailvod.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.tv.mailvod.R
import com.tv.mailvod.databinding.ActivitySearchBinding

/**
 * 搜索页（界面壳, 搜索逻辑暂未实现）。
 * - 左上角返回按钮 = 遥控器返回键（系统默认返回即 finish）
 * - 搜索框 + 搜索按钮, 下方结果列表 (item_search: 标题 + 摘要 + 想看按钮)
 */
class SearchActivity : ComponentActivity() {

    private lateinit var binding: ActivitySearchBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSearch.setOnClickListener {
            Toast.makeText(this, R.string.search_todo, Toast.LENGTH_SHORT).show()
        }
    }
}
