package com.xiaobai.hongbao.util

import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * 统一日志：同时输出到 Xposed 日志和 logcat（tag=Hongbao）。
 * 真机排查：adb logcat | grep Hongbao
 */
object L {
    const val TAG = "Hongbao"

    fun d(msg: String) {
        Log.d(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {
        }
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        try {
            XposedBridge.log("[$TAG] $msg")
            if (t != null) XposedBridge.log(Log.getStackTraceString(t))
        } catch (_: Throwable) {
        }
    }
}
