package com.xiaobai.hongbao.hook

import com.xiaobai.hongbao.util.L
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Constructor
import java.util.concurrent.ConcurrentHashMap

/**
 * 抢红包编排：
 *   领取(NetSceneReceiveLuckyMoney) -> 回调成功 -> 拆开(NetSceneOpenLuckyMoney)
 * 全程走微信网络场景队列，不依赖任何 UI，所以息屏可抢。
 *
 * 注意：NetSceneReceive/Open 的构造函数签名随微信版本而变。这里：
 *   1）启动时把候选构造函数全部打到日志（见 WxClassResolver.dumpCtors）
 *   2）用启发式按参数类型填充（sendId/channelId/msgType/nativeUrl 等）
 *   3）真机一轮 logcat 即可据实际签名固化（见 README 校准步骤）
 */
class LuckyMoneyGrabber(private val resolver: WxClassResolver) {

    // 去重：同一个红包只抢一次
    private val handled = ConcurrentHashMap.newKeySet<String>()

    fun tryGrab(rp: RedPacket) {
        if (!handled.add(rp.sendId)) {
            L.d("dup sendId=${rp.sendId}, skip")
            return
        }
        L.d("GRAB group=${rp.talker} sender=${rp.senderWxid} sendId=${rp.sendId} channelId=${rp.channelId} msgType=${rp.msgType} scene=${rp.scene}")

        val receiveCls = resolver.receiveSceneClass
        if (receiveCls == null) {
            L.e("receiveSceneClass=null -> 仅检测到红包，未能定位领取场景（需校准 DexKit 锚点）")
            return
        }

        val delay = Config.randomDelayMs()
        if (delay > 0) {
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
            }
        }

        try {
            val scene = buildReceiveScene(receiveCls, rp)
            if (scene == null) {
                L.e("无法构造领取场景（构造函数不匹配），sendId=${rp.sendId}")
                return
            }
            hookSceneEndToOpen(scene, rp)
            val ok = resolver.enqueue(scene)
            L.d("enqueue receive scene ok=$ok sendId=${rp.sendId}")
        } catch (t: Throwable) {
            L.e("tryGrab failed sendId=${rp.sendId}", t)
        }
    }

    /**
     * 启发式构造领取场景：
     * 选择参数数量最多、且类型可由红包字段填充的构造函数。
     * String 参数按出现顺序填 sendId / channelId / nativeUrl / talker / sender；
     * int 参数填 channelId / msgType / scene。
     */
    private fun buildReceiveScene(cls: Class<*>, rp: RedPacket): Any? {
        val ctor = pickCtor(cls) ?: return null
        val args = fillArgs(ctor, rp)
        L.d("receive ctor(${ctor.parameterTypes.joinToString(",") { it.simpleName }}) args=${args.joinToString(",") { it?.toString()?.take(24) ?: "null" }}")
        return ctor.newInstance(*args)
    }

    private fun pickCtor(cls: Class<*>): Constructor<*>? {
        // 优先包含 String 参数的构造函数（红包 id 必为 String），参数多者优先
        return cls.declaredConstructors
            .sortedByDescending { it.parameterTypes.size }
            .firstOrNull { c -> c.parameterTypes.any { it == String::class.java } }
            ?.apply { isAccessible = true }
            ?: cls.declaredConstructors.maxByOrNull { it.parameterTypes.size }?.apply { isAccessible = true }
    }

    private fun fillArgs(ctor: Constructor<*>, rp: RedPacket): Array<Any?> {
        val stringQueue = ArrayDeque(listOf(rp.sendId, rp.channelId, rp.nativeUrl, rp.talker, rp.senderWxid, ""))
        val intQueue = ArrayDeque(
            listOf(
                rp.channelId.toIntOrNull() ?: 1,
                rp.msgType.toIntOrNull() ?: 1,
                rp.scene
            )
        )
        return ctor.parameterTypes.map { p ->
            when (p) {
                String::class.java -> stringQueue.removeFirstOrNull() ?: ""
                Int::class.javaPrimitiveType, Integer::class.java -> intQueue.removeFirstOrNull() ?: 0
                Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> false
                Long::class.javaPrimitiveType, java.lang.Long::class.java -> 0L
                else -> null
            }
        }.toTypedArray()
    }

    /**
     * 领取成功后链式拆开。hook 领取场景对象的回调方法（onGYNetEnd / onSceneEnd 之类，
     * 通过 NetSceneBase 的方法名定位），成功则构造并投递拆开场景。
     * 若拆开场景类未定位到，则至少完成领取（部分版本领取即入账群红包详情）。
     */
    private fun hookSceneEndToOpen(receiveScene: Any, rp: RedPacket) {
        val openCls = resolver.openSceneClass
        if (openCls == null) {
            L.d("openSceneClass=null -> 仅领取，不链式拆开（需校准）")
            return
        }
        // 找 NetSceneBase 上形如 onXxx(int,int,int,String,NetSceneBase?...) 的回调方法
        val base = resolver.netSceneBaseClass
        val callback = base.declaredMethods.firstOrNull { m ->
            val pt = m.parameterTypes
            pt.size >= 3 && pt[0] == Int::class.javaPrimitiveType &&
                pt[1] == Int::class.javaPrimitiveType && pt[2] == Int::class.javaPrimitiveType
        }
        if (callback == null) {
            L.e("未找到 NetSceneBase 回调方法，无法链式拆开")
            return
        }
        try {
            XposedBridge.hookMethod(callback, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.thisObject !== receiveScene) return
                    try {
                        val errType = param.args.getOrNull(1) as? Int ?: -1
                        val errCode = param.args.getOrNull(2) as? Int ?: -1
                        L.d("receive onSceneEnd errType=$errType errCode=$errCode sendId=${rp.sendId}")
                        if (errType == 0 && errCode == 0) {
                            openScene(openCls, rp)
                        }
                    } catch (t: Throwable) {
                        L.e("chain open failed", t)
                    }
                }
            })
        } catch (t: Throwable) {
            L.e("hook scene callback failed", t)
        }
    }

    private fun openScene(openCls: Class<*>, rp: RedPacket) {
        val ctor = pickCtor(openCls) ?: run { L.e("open ctor not found"); return }
        val args = fillArgs(ctor, rp)
        L.d("open ctor(${ctor.parameterTypes.joinToString(",") { it.simpleName }}) sendId=${rp.sendId}")
        val scene = ctor.newInstance(*args)
        val ok = resolver.enqueue(scene)
        L.d("enqueue open scene ok=$ok sendId=${rp.sendId}")
    }
}
