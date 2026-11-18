package com.xiaobai.hongbao.hook

import com.xiaobai.hongbao.util.L
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * 用 DexKit 在微信（混淆后）dex 里按字符串特征定位抢红包需要的类与方法：
 *  - NetSceneQueue + doScene(NetSceneBase,int):boolean + 静态获取队列的方法（投递网络场景的入口，复用自比价模块已验证逻辑）
 *  - NetSceneReceiveLuckyMoney（领取红包，CGI receivewxhb）
 *  - NetSceneOpenLuckyMoney（拆红包，CGI openwxhb）
 *
 * 微信 8.0.40：类名混淆，但日志 TAG / CGI url 字符串相对稳定，作为匹配锚点。
 */
class WxClassResolver(private val classLoader: ClassLoader) {

    @Volatile
    var resolved: Boolean = false
        private set

    lateinit var netSceneBaseClass: Class<*>
        private set
    lateinit var netSceneQueueClass: Class<*>
        private set
    lateinit var getNetSceneQueueMethod: Method
        private set
    lateinit var doSceneMethod: Method
        private set

    // 抢红包相关（可能为 null：未匹配到时降级，仅检测+日志）
    var receiveSceneClass: Class<*>? = null
        private set
    var openSceneClass: Class<*>? = null
        private set

    fun resolve(hostApkPath: String) {
        if (resolved) return
        L.d("resolver start apk=$hostApkPath")
        DexKitBridge.create(hostApkPath).use { bridge ->
            resolveQueueAndDoScene(bridge)
            resolveLuckyMoneyScenes(bridge)
        }
        resolved = true
        L.d("resolver done (receive=${receiveSceneClass?.name} open=${openSceneClass?.name})")
    }

    private fun resolveQueueAndDoScene(bridge: DexKitBridge) {
        val baseCd = bridge.findClass {
            matcher { methods { add { usingStrings("scene security verification not passed, type=") } } }
        }.firstOrNull() ?: throw IllegalStateException("NetSceneBase not found")

        val queueCd = bridge.findClass {
            matcher { methods { add { usingStrings("MicroMsg.NetSceneQueue", "worker thread has not been se") } } }
        }.firstOrNull() ?: throw IllegalStateException("NetSceneQueue not found")

        val baseName = baseCd.name
        val queueName = queueCd.name
        netSceneBaseClass = baseCd.getInstance(classLoader)
        netSceneQueueClass = queueCd.getInstance(classLoader)
        L.d("NetSceneBase=$baseName NetSceneQueue=$queueName")

        val doSceneHit = bridge.findMethod {
            matcher {
                declaredClass = queueName
                paramTypes(baseName, "int")
                returnType = "boolean"
            }
        }.firstOrNull() ?: throw IllegalStateException("doScene(NetSceneBase,int):boolean not found")
        doSceneMethod = doSceneHit.getMethodInstance(classLoader).apply { isAccessible = true }

        val getterHit = bridge.findMethod {
            matcher {
                paramCount = 0
                returnType = queueName
                modifiers = java.lang.reflect.Modifier.STATIC
            }
        }.firstOrNull() ?: throw IllegalStateException("static getNetSceneQueue() not found")
        getNetSceneQueueMethod = getterHit.getMethodInstance(classLoader).apply { isAccessible = true }
        L.d("doScene=${doSceneMethod.declaringClass.name}.${doSceneMethod.name} getQueue=${getNetSceneQueueMethod.declaringClass.name}.${getNetSceneQueueMethod.name}")
    }

    private fun resolveLuckyMoneyScenes(bridge: DexKitBridge) {
        receiveSceneClass = findSceneClass(
            bridge,
            anchors = listOf("MicroMsg.NetSceneReceiveLuckyMoney", "/cgi-bin/mmpay-bin/receivewxhb", "receivewxhb"),
            label = "ReceiveLuckyMoney"
        )
        openSceneClass = findSceneClass(
            bridge,
            anchors = listOf("MicroMsg.NetSceneOpenLuckyMoney", "/cgi-bin/mmpay-bin/openwxhb", "openwxhb"),
            label = "OpenLuckyMoney"
        )
        dumpCtors("ReceiveLuckyMoney", receiveSceneClass)
        dumpCtors("OpenLuckyMoney", openSceneClass)
    }

    private fun findSceneClass(bridge: DexKitBridge, anchors: List<String>, label: String): Class<*>? {
        for (s in anchors) {
            try {
                val cd = bridge.findClass {
                    matcher { methods { add { usingStrings(s) } } }
                }.firstOrNull()
                if (cd != null) {
                    L.d("$label matched by \"$s\" -> ${cd.name}")
                    return cd.getInstance(classLoader)
                }
            } catch (t: Throwable) {
                L.e("$label find by \"$s\" failed", t)
            }
        }
        L.e("$label NOT found by any anchor=$anchors")
        return null
    }

    private fun dumpCtors(label: String, cls: Class<*>?) {
        if (cls == null) return
        for (c in cls.declaredConstructors) {
            L.d("$label.ctor(${c.parameterTypes.joinToString(",") { it.name }})")
        }
    }

    /** 把一个已构造好的 NetScene 投递到队列。返回 doScene 的布尔结果。 */
    fun enqueue(scene: Any): Boolean {
        val queue = getNetSceneQueueMethod.invoke(null)
            ?: run { L.e("getNetSceneQueue() returned null"); return false }
        val r = doSceneMethod.invoke(queue, scene, 0)
        return (r as? Boolean) ?: false
    }

    fun findCtor(cls: Class<*>, predicate: (Constructor<*>) -> Boolean): Constructor<*>? =
        cls.declaredConstructors.firstOrNull(predicate)?.apply { isAccessible = true }
}
