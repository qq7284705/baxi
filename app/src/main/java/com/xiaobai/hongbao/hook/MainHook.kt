package com.xiaobai.hongbao.hook

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.PowerManager
import com.xiaobai.hongbao.util.L
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 入口。逻辑：
 *  1) 仅在微信进程生效；
 *  2) Hook 微信 WCDB 的 insertWithOnConflict，拦截写入 message/AppMessage 表的新消息——
 *     这一步在息屏时同样触发（数据库写入与 UI 无关），是「息屏感知红包」的核心；
 *  3) 命中群红包则交给 LuckyMoneyGrabber 走网络场景领取/拆开；
 *  4) PARTIAL_WAKE_LOCK 保活，保证息屏后 CPU 能处理。
 */
class MainHook : IXposedHookZygoteInit, IXposedHookLoadPackage {

    private val WX_PACKAGE = "com.tencent.mm"

    private var modulePath: String? = null
    private var wxContext: Context? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var resolverInitStarted = false
    private var resolver: WxClassResolver? = null
    private var grabber: LuckyMoneyGrabber? = null

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
        L.d("initZygote modulePath=$modulePath")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != WX_PACKAGE) return
        val isMain = lpparam.processName == WX_PACKAGE
        L.d("loaded into ${lpparam.processName} (main=$isMain)")

        try {
            hookDatabaseInsert(lpparam)
        } catch (t: Throwable) {
            L.e("hookDatabaseInsert failed", t)
        }
        if (isMain) {
            try {
                Config.init("com.xiaobai.hongbao")
                hookApplicationContext(lpparam)
            } catch (t: Throwable) {
                L.e("init failed", t)
            }
        }
    }

    private fun hookApplicationContext(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            "android.app.Application", lpparam.classLoader, "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Application ?: return
                    if (app.packageName != WX_PACKAGE) return
                    if (wxContext != null) return
                    wxContext = app.applicationContext
                    L.d("wx context ready")
                    acquireWakeLock()
                    initResolver(lpparam, app)
                }
            }
        )
    }

    private fun initResolver(lpparam: XC_LoadPackage.LoadPackageParam, app: Application) {
        if (resolverInitStarted) return
        resolverInitStarted = true
        Thread({
            try {
                val hostApk = app.applicationInfo.sourceDir
                val r = WxClassResolver(lpparam.classLoader)
                r.resolve(hostApk)
                resolver = r
                grabber = LuckyMoneyGrabber(r)
                L.d("grabber ready")
            } catch (t: Throwable) {
                L.e("resolver init FAILED", t)
            }
        }, "Hongbao-ResolverInit").start()
    }

    private fun hookDatabaseInsert(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val table = param.args[0] as? String ?: return
                    if (table != "message" && table != "AppMessage") return
                    val values = param.args[2] as? ContentValues ?: return
                    onMessageInserted(values)
                } catch (_: Throwable) {
                }
            }
        }
        val classes = listOf(
            "com.tencent.wcdb.database.SQLiteDatabase",
            "com.tencent.wcdb.compat.SQLiteDatabase"
        )
        var ok = 0
        for (cn in classes) {
            try {
                XposedHelpers.findAndHookMethod(
                    cn, lpparam.classLoader, "insertWithOnConflict",
                    String::class.java, String::class.java, ContentValues::class.java, Int::class.javaPrimitiveType, cb
                )
                L.d("WCDB hook OK: $cn (${lpparam.processName})")
                ok++
            } catch (t: Throwable) {
                L.d("WCDB hook skip $cn: ${t.message}")
            }
        }
        if (ok == 0) L.d("WCDB hook NONE in ${lpparam.processName}")
    }

    private fun onMessageInserted(values: ContentValues) {
        if (!Config.enabled) return
        val type = values.getAsInteger("type")
        val content = values.getAsString("content") ?: return
        if (!RedPacketParser.looksLikeRedPacket(type, content)) return

        val talker = values.getAsString("talker") ?: return
        val isGroup = talker.contains("@chatroom")
        if (Config.groupOnly && !isGroup) {
            L.d("非群红包，按配置跳过 talker=$talker")
            return
        }
        // 自己发的红包不抢
        val isSend = values.getAsInteger("isSend")
        if (isSend != null && isSend == 1) return

        val rp = RedPacketParser.parse(talker, content)
        if (rp == null) {
            L.d("疑似红包但解析失败 talker=$talker contentHead=${content.take(120)}")
            return
        }
        L.d("检测到群红包 group=${rp.talker} sendId=${rp.sendId}")
        val g = grabber
        if (g == null) {
            L.d("grabber 尚未就绪（DexKit 解析中），本次仅记录 sendId=${rp.sendId}")
            return
        }
        Thread({ g.tryGrab(rp) }, "Hongbao-Grab").start()
    }

    private fun acquireWakeLock() {
        try {
            val ctx = wxContext ?: return
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hongbao::Lock")
            wl.acquire(24 * 60 * 60 * 1000L)
            wakeLock = wl
            L.d("wakelock ok")
        } catch (t: Throwable) {
            L.e("wakelock failed", t)
        }
    }
}
