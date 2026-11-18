package com.xiaobai.hongbao.hook

import com.xiaobai.hongbao.util.L
import de.robv.android.xposed.XSharedPreferences

/**
 * 模块配置。MainActivity 写入，WeChat 进程内通过 XSharedPreferences 读取。
 * 需要 LSPosed 的「世界可读 SharedPreferences」支持（manifest 中可加 xposedsharedprefs）。
 * 读不到时使用默认值（默认：开启、仅群红包、随机延迟 0~800ms）。
 */
object Config {
    const val PREF_NAME = "hongbao_config"

    const val KEY_ENABLED = "enabled"
    const val KEY_GROUP_ONLY = "group_only"
    const val KEY_MIN_DELAY = "min_delay_ms"
    const val KEY_MAX_DELAY = "max_delay_ms"

    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    var groupOnly: Boolean = true
        private set

    @Volatile
    var minDelayMs: Int = 0
        private set

    @Volatile
    var maxDelayMs: Int = 800
        private set

    private var prefs: XSharedPreferences? = null

    fun init(modulePackage: String) {
        try {
            val p = XSharedPreferences(modulePackage, PREF_NAME)
            p.makeWorldReadable()
            prefs = p
            reload()
            L.d("Config loaded enabled=$enabled groupOnly=$groupOnly delay=$minDelayMs~$maxDelayMs (readable=${p.file.canRead()})")
        } catch (t: Throwable) {
            L.e("Config init failed, using defaults", t)
        }
    }

    fun reload() {
        val p = prefs ?: return
        try {
            p.reload()
            enabled = p.getBoolean(KEY_ENABLED, true)
            groupOnly = p.getBoolean(KEY_GROUP_ONLY, true)
            minDelayMs = p.getInt(KEY_MIN_DELAY, 0)
            maxDelayMs = p.getInt(KEY_MAX_DELAY, 800)
            if (maxDelayMs < minDelayMs) maxDelayMs = minDelayMs
        } catch (_: Throwable) {
        }
    }

    fun randomDelayMs(): Long {
        reload()
        val lo = minDelayMs.coerceAtLeast(0)
        val hi = maxDelayMs.coerceAtLeast(lo)
        return if (hi <= lo) lo.toLong() else (lo + (Math.random() * (hi - lo)).toLong())
    }
}
