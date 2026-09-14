package com.tv.mailvod.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tv.mailvod.R
import com.tv.mailvod.databinding.ActivitySearchBinding

/**
 * 搜索页（界面壳, 搜索逻辑暂未实现）。
 * - 左上角返回按钮
 * - 搜索框 + 搜索按钮, 下方结果列表(后续实现)
 */
class SearchActivity : AppCompatActivity() {

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
