package com.tv.mailvod.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tv.mailvod.App
import com.tv.mailvod.R
import com.tv.mailvod.config.Config

/**
 * 首次配置向导：遥控器/软键盘输入 163 邮箱与授权码, 保存到 files/config.json。
 * host/port/subject_prefix 使用默认值, 不在向导中展开。
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var etUser: EditText
    private lateinit var etAuth: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(getColor(R.color.bg))
        }
        fun title(text: String): TextView = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_secondary))
            textSize = 14f
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.setup_title)
            setTextColor(getColor(R.color.text_primary))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(title(getString(R.string.setup_user_hint)))
        etUser = EditText(this).apply {
            hint = "example@163.com"
            setSingleLine(true)
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
        }
        root.addView(etUser)
        root.addView(title(getString(R.string.setup_auth_hint)))
        etAuth = EditText(this).apply {
            hint = "16 位授权码"
            setSingleLine(true)
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
        }
        root.addView(etAuth)
        root.addView(title(getString(R.string.setup_note)))
        root.addView(Button(this).apply {
            text = getString(R.string.setup_save)
            setOnClickListener { onSave() }
        })
        setContentView(root)
    }

    /** 校验非空后保存配置并关闭向导, 回到列表页。 */
    private fun onSave() {
        val user = etUser.text.toString().trim()
        val auth = etAuth.text.toString().trim()
        if (user.isEmpty() || auth.isEmpty()) {
            Toast.makeText(this, R.string.setup_missing, Toast.LENGTH_LONG).show()
            return
        }
        val cfg = Config(mail = Config.Mail(user = user, authCode = auth))
        App.instance.configLoader.save(cfg)
        Toast.makeText(this, R.string.setup_done, Toast.LENGTH_SHORT).show()
        finish()
    }
}
