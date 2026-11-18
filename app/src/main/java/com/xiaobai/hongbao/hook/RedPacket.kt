package com.xiaobai.hongbao.hook

/**
 * 解析出的群红包信息。字段来源于 message 表 content 里的 <appmsg><wcpayinfo> XML
 * 以及 nativeurl（wxpay://.../receivewxhb?...）中的查询参数。
 */
data class RedPacket(
    val talker: String,          // 群 id，xxxx@chatroom
    val senderWxid: String,      // 发红包的人 wxid
    val nativeUrl: String,       // wxpay://c2cbizmessagehandler/hongbaoremittancehandler/receivewxhb?...
    val sendId: String,          // 红包 id
    val channelId: String,       // 渠道 id
    val msgType: String,         // nativeurl 里的 msgtype
    val ver: String,             // 协议版本
    val sign: String,            // 签名
    val scene: Int               // 场景值，群红包通常为 1002
)

object RedPacketParser {

    // message 表里红包/转账等支付类 appmsg 的消息 type
    const val WX_MSG_TYPE_LUCKY_MONEY = 436207665 // 0x1A000031

    /** 是否疑似红包消息（用于在数据库 hook 里快速过滤）。 */
    fun looksLikeRedPacket(type: Int?, content: String?): Boolean {
        if (content == null) return false
        if (type != null && type == WX_MSG_TYPE_LUCKY_MONEY) {
            // 仍需排除「已被领完/转账」等：靠下面的 nativeurl 关键字进一步判断
        }
        return content.contains("receivewxhb") ||
            content.contains("wxpay://c2cbizmessagehandler/hongbao") ||
            (content.contains("<type>2001</type>") && content.contains("<wcpayinfo>"))
    }

    /**
     * 解析群红包。talker 必须由调用方从 message 行的 talker 字段提供（@chatroom）。
     * 返回 null 表示无法解析（非红包 / 缺关键字段 / 已是「拆开详情」类消息等）。
     */
    fun parse(talker: String, content: String): RedPacket? {
        try {
            // 群消息 content 形如 "wxid_sender:\n<msg>...</msg>"，先剥离发送者前缀
            var senderFromPrefix = ""
            var xml = content
            val sep = content.indexOf(":\n")
            if (sep in 0..64) {
                senderFromPrefix = content.substring(0, sep)
                xml = content.substring(sep + 2)
            }

            val nativeUrl = unescape(extractTag(xml, "nativeurl") ?: extractTag(xml, "nativeUrl") ?: return null)
            if (!nativeUrl.contains("receivewxhb") && !nativeUrl.contains("hongbao")) return null

            val sendId = queryParam(nativeUrl, "sendid") ?: extractTag(xml, "sendid") ?: return null
            val channelId = queryParam(nativeUrl, "channelid") ?: "1"
            val msgType = queryParam(nativeUrl, "msgtype") ?: "1"
            val ver = queryParam(nativeUrl, "ver") ?: "8"
            val sign = queryParam(nativeUrl, "sign") ?: ""
            val sceneId = (extractTag(xml, "sceneid") ?: queryParam(nativeUrl, "scene") ?: "1002")
                .toIntOrNull() ?: 1002

            val sender = (extractTag(xml, "fromusername") ?: senderFromPrefix).trim()

            return RedPacket(
                talker = talker,
                senderWxid = sender,
                nativeUrl = nativeUrl,
                sendId = sendId,
                channelId = channelId,
                msgType = msgType,
                ver = ver,
                sign = sign,
                scene = sceneId
            )
        } catch (t: Throwable) {
            return null
        }
    }

    private fun extractTag(xml: String, tag: String): String? {
        val open = "<$tag>"
        val close = "</$tag>"
        var s = xml.indexOf(open)
        if (s < 0) return null
        s += open.length
        val e = xml.indexOf(close, s)
        if (e < 0) return null
        var v = xml.substring(s, e).trim()
        // 去掉 CDATA
        if (v.startsWith("<![CDATA[") && v.endsWith("]]>")) {
            v = v.substring("<![CDATA[".length, v.length - "]]>".length)
        }
        return v
    }

    private fun queryParam(url: String, key: String): String? {
        val q = url.indexOf('?')
        val query = if (q >= 0) url.substring(q + 1) else url
        for (pair in query.split("&", "&amp;")) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            if (pair.substring(0, idx).equals(key, ignoreCase = true)) {
                return pair.substring(idx + 1)
            }
        }
        return null
    }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}
