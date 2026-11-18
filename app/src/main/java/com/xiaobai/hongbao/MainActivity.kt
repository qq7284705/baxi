package com.xiaobai.hongbao

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.xiaobai.hongbao.hook.Config

/**
 * 配置界面：开关、仅群红包、随机延迟。写入世界可读 SharedPreferences，
 * 供 LSPosed 注入到微信进程后通过 XSharedPreferences 读取。
 */
class MainActivity : AppCompatActivity() {

    @SuppressLint("UseSwitchCompatOrMaterialCode", "WorldReadableFiles")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = try {
            getSharedPreferences(Config.PREF_NAME, Context.MODE_WORLD_READABLE)
        } catch (e: SecurityException) {
            getSharedPreferences(Config.PREF_NAME, Context.MODE_PRIVATE)
        }

        val pad = (resources.displayMetrics.density * 20).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "群红包助手 · 息屏抢红包"
            textSize = 20f
        })
        root.addView(TextView(this).apply {
            text = "需在 LSPosed 启用本模块并勾选作用域 com.tencent.mm。日志：adb logcat | grep Hongbao"
            textSize = 12f
            setPadding(0, pad / 2, 0, pad)
        })

        val swEnabled = Switch(this).apply {
            text = "启用抢红包"
            isChecked = prefs.getBoolean(Config.KEY_ENABLED, true)
        }
        val swGroup = Switch(this).apply {
            text = "仅抢群红包"
            isChecked = prefs.getBoolean(Config.KEY_GROUP_ONLY, true)
        }
        val etMin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "最小延迟(ms)"
            setText(prefs.getInt(Config.KEY_MIN_DELAY, 0).toString())
        }
        val etMax = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "最大延迟(ms)"
            setText(prefs.getInt(Config.KEY_MAX_DELAY, 800).toString())
        }
        root.addView(swEnabled)
        root.addView(swGroup)
        root.addView(TextView(this).apply { text = "抢红包随机延迟（防风控，建议 0~1000ms）"; setPadding(0, pad / 2, 0, 0) })
        root.addView(etMin)
        root.addView(etMax)

        root.addView(Button(this).apply {
            text = "保存"
            setOnClickListener {
                prefs.edit()
                    .putBoolean(Config.KEY_ENABLED, swEnabled.isChecked)
                    .putBoolean(Config.KEY_GROUP_ONLY, swGroup.isChecked)
                    .putInt(Config.KEY_MIN_DELAY, etMin.text.toString().toIntOrNull() ?: 0)
                    .putInt(Config.KEY_MAX_DELAY, etMax.text.toString().toIntOrNull() ?: 800)
                    .apply()
                Toast.makeText(this@MainActivity, "已保存（重启微信生效）", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(root)
    }
}
