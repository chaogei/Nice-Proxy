package com.niceproxy.core.config.share

import java.io.ByteArrayOutputStream
import kotlin.io.encoding.Base64

/**
 * 分享链接的通用拆解结果。
 *
 * 各家客户端生成的链接在细节上千差万别（大小写、编码方式、字段别名），
 * 但结构基本一致：`scheme://userinfo@host:port/path?query#fragment`。
 * 先统一拆成这个结构，各协议解析器只关心自己的字段。
 */
internal data class ParsedUri(
    val scheme: String,
    val userInfo: String,
    val host: String,
    val port: Int,
    val path: String,
    val query: Map<String, String>,
    val fragment: String,
) {
    fun q(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> query[key]?.takeIf { it.isNotBlank() } }

    fun qBool(vararg keys: String): Boolean =
        q(*keys)?.lowercase()?.let { it in TRUTHY } ?: false

    fun qInt(vararg keys: String): Int? = q(*keys)?.toIntOrNull()

    /** 节点名，取自 fragment；为空时回退到 `host:port`。 */
    fun displayName(): String = fragment.ifBlank { "$host:$port" }

    private companion object {
        /** `insecure=yes` 出自 Clash 转换器，`on` 出自部分面板模板。 */
        val TRUTHY = setOf("1", "true", "yes", "on")
    }
}

internal object UriSupport {

    /**
     * 剪贴板里的链接经常夹着看不见的字符。
     *
     * Windows 记事本存的 UTF-8 文件带 BOM，网页与聊天软件复制出来会混入
     * 零宽空格和不换行空格。它们都不是 [String.trim] 认的空白字符，
     * 留着会让 scheme 判断、Base64 解码、UUID 比对统统失败 ——
     * 而用户在界面上完全看不出这条链接跟别的有什么不同。
     */
    fun sanitize(raw: String): String {
        val trimmed = raw.trim { it.isWhitespace() || Character.isSpaceChar(it) }
        return if (trimmed.none { it in INVISIBLE }) trimmed else trimmed.filterNot { it in INVISIBLE }
    }

    /**
     * 手写拆解而不用 [java.net.URI]。
     *
     * java.net.URI 对分享链接里常见的写法过于严格：密码含 `/` `:` 等未编码字符、
     * fragment 里有中文和 emoji、IPv6 字面量写法不规范，都会直接抛异常。
     * 用户导入失败时只会怪应用不好用，不会去修链接。
     */
    fun parse(raw: String): ParsedUri? {
        val trimmed = sanitize(raw)
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        var rest = trimmed.substring(schemeEnd + 3)

        val fragment = rest.substringAfter('#', "").let { decode(it) }
        rest = rest.substringBefore('#')

        val queryString = rest.substringAfter('?', "")
        rest = rest.substringBefore('?')

        // userinfo 里可能含 '@'（例如未编码的密码），取最后一个 '@' 作为分隔
        val atIndex = rest.lastIndexOf('@')
        val userInfo = if (atIndex >= 0) rest.substring(0, atIndex) else ""
        var authority = if (atIndex >= 0) rest.substring(atIndex + 1) else rest

        val slashIndex = authority.indexOf('/')
        val path = if (slashIndex >= 0) authority.substring(slashIndex) else ""
        if (slashIndex >= 0) authority = authority.substring(0, slashIndex)

        val (host, port) = splitHostPort(authority, defaultPortFor(scheme)) ?: return null
        if (host.isBlank()) return null

        return ParsedUri(
            scheme = scheme,
            userInfo = userInfo,
            host = host,
            port = port,
            path = path,
            query = parseQuery(queryString),
            fragment = fragment,
        )
    }

    /**
     * 链接省略端口时的兜底值。
     *
     * 面板生成的 trojan / hysteria2 链接经常省掉 443，按 URI 规范这是合法的，
     * 但少了端口整条链接就解析不出来。
     *
     * 刻意**不给 http / https 兜底**：它们和普通网址长得一模一样，补上默认端口后，
     * 订阅正文里一条「官网地址」公告就会被当成 HTTP 代理节点导进来。
     */
    private fun defaultPortFor(scheme: String): Int? = when (scheme) {
        "trojan", "vless", "vmess", "hysteria2", "hy2", "hysteria", "hy", "tuic", "anytls" -> 443
        "socks", "socks5", "socks4", "socks4a" -> 1080
        "ssh" -> 22
        else -> null
    }

    /** 兼容 IPv6 字面量 `[2001:db8::1]:443`。 */
    fun splitHostPort(authority: String, defaultPort: Int? = null): Pair<String, Int>? {
        if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            val host = authority.substring(1, close)
            val tail = authority.substring(close + 1).removePrefix(":")
            val port = if (tail.isEmpty()) defaultPort else tail.toIntOrNull()
            return host to (port ?: return null)
        }
        val colon = authority.lastIndexOf(':')
        // 以 ':' 开头说明主机名缺失，不是「省略端口」
        if (colon == 0) return null
        if (colon < 0) return authority to (defaultPort ?: return null)
        val port = authority.substring(colon + 1).toIntOrNull() ?: return null
        return authority.substring(0, colon) to port
    }

    fun parseQuery(query: String): Map<String, String> =
        query.split('&')
            .filter { it.isNotBlank() }
            .mapNotNull { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                if (key.isBlank()) null else decode(key).lowercase() to decode(value)
            }
            .toMap()

    /**
     * 百分号解码。
     *
     * 不用 [java.net.URLDecoder]：它按 form-urlencoded 规则把 `+` 解成空格，
     * 而 `+` 是标准 Base64 表的字符 —— Shadowsocks 密码、REALITY 公钥里都会出现，
     * 换成空格得到的是一个「导入成功但连不上」的节点，比导入失败还难排查。
     *
     * 另外 URLDecoder 撞上半截转义序列（节点名里的 `50%off`）会整串抛异常，
     * 这里改成把无法识别的 `%` 原样留下，只解真正合法的 `%XX`。
     */
    fun decode(value: String): String {
        if (value.indexOf('%') < 0) return value
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            if (value[i] == '%' && i + 2 < value.length) {
                val high = Character.digit(value[i + 1], 16)
                val low = Character.digit(value[i + 2], 16)
                if (high >= 0 && low >= 0) {
                    out.write((high shl 4) or low)
                    i += 3
                    continue
                }
            }
            // 整段按 UTF-8 写回，逐字符写会拆散 emoji 的代理对
            val next = value.indexOf('%', i + 1).let { if (it < 0) value.length else it }
            out.write(value.substring(i, next).toByteArray(Charsets.UTF_8))
            i = next
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /**
     * 宽容的 Base64 解码。
     *
     * 分享链接里的 Base64 五花八门：标准表 / URL 安全表、带填充 / 不带填充、
     * 中间夹换行。任选一种严格解码器都会漏掉相当一部分真实链接。
     *
     * 用 [kotlin.io.encoding.Base64] 而不是 `java.util.Base64`：后者要 API 26，
     * 而 minSdk 是 24。本模块是纯 JVM 模块，编译和单测都在桌面 JVM 上跑，
     * 用错了这里一路绿灯，只有 Android 7.x 真机会 NoClassDefFoundError。
     */
    fun decodeBase64(value: String): String? {
        val cleaned = buildString(value.length) {
            for (c in value) {
                if (c.isWhitespace() || Character.isSpaceChar(c) || c in INVISIBLE) continue
                // 出现字母表以外的字符就直接判定为「这段不是 Base64」。
                // 少了这道闸，MIME 解码器会把 `uuid@host:443` 里的 @ . : 悄悄跳过，
                // 硬解出一串二进制垃圾，调用方还以为解码成功了。
                if (!isBase64Char(c)) return null
                append(c)
            }
        }
        if (cleaned.isEmpty()) return null
        val padded = if (cleaned.length % 4 == 0) {
            cleaned
        } else {
            cleaned.padEnd((cleaned.length + 3) / 4 * 4, '=')
        }
        // 顺序有讲究：URL 安全表在前，因为 Mime 解码器用的是标准表，
        // 它会把 '-' '_' 当作无关字符**静默跳过**而不是报错，
        // 于是一段 URL 安全的 Base64 会被硬解成二进制垃圾。
        for (decoder in DECODERS) {
            val result = runCatching { String(decoder.decode(padded), Charsets.UTF_8) }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    private val DECODERS = listOf(Base64.UrlSafe, Base64.Default, Base64.Mime)

    /**
     * 判断一段文本整体是否像 Base64（用于订阅内容的格式探测）。
     *
     * 逐字符扫描而不是先正则去空白再判断：订阅正文动辄几 MB，
     * 每做一次 `replace` 就多复制一份完整内容。
     */
    fun looksLikeBase64(value: String): Boolean {
        var count = 0
        for (c in value) {
            if (c.isWhitespace() || Character.isSpaceChar(c) || c in INVISIBLE) continue
            if (!isBase64Char(c)) return false
            count++
        }
        return count >= MIN_BASE64_LENGTH
    }

    /** 标准表与 URL 安全表的并集，加上填充符。 */
    private fun isBase64Char(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
            c == '+' || c == '/' || c == '-' || c == '_' || c == '='

    /** BOM、零宽字符与方向标记：肉眼不可见，但会破坏一切按字符比对的逻辑。 */
    private val INVISIBLE = setOf(
        '\uFEFF', '\u200B', '\u200C', '\u200D', '\u2060', '\u200E', '\u200F',
    )

    private const val MIN_BASE64_LENGTH = 16
}
