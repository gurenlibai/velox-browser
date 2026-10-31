package com.velox.browser.util

import java.net.IDN
import java.util.Locale

/**
 * 输入解析：区分「URL」与「搜索词」。
 * 规则（刻意保守，避免把搜索词当域名去连）：
 *  1. 已带 scheme → 原样；
 *  2. 形如 host[:port][/path] 且 host 含点 且顶级域合法 → 补 https://；
 *  3. localhost / IP 字面量 → 补 http://；
 *  4. 其余 → 搜索。
 */
object Urls {

    private val TLD = Regex("^[a-z0-9-]{2,63}$")
    private val IPV4 = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")

    fun toUrlOrSearch(input: String, searchBase: String): String {
        val raw = input.trim()
        if (raw.isEmpty()) return searchBase
        if (raw.contains("://")) return raw
        if (raw.startsWith("about:") || raw.startsWith("data:") || raw.startsWith("file:")) return raw

        val hostPart = raw.takeWhile { it != '/' && it != '?' && it != '#' && it != ':' }
        val host = hostPart.lowercase(Locale.ROOT)

        if (host == "localhost" || IPV4.matches(host)) return "http://" + raw

        val labels = host.split(".")
        val looksLikeHost = labels.size >= 2 &&
            TLD.matches(labels.last()) &&
            labels.all { it.isNotEmpty() && it.length <= 63 && it.all { c -> c.isLetterOrDigit() || c == '-' } } &&
            labels.last().any { it.isLetter() }

        if (!looksLikeHost) return searchBase + encodeQuery(raw)

        val rest = raw.substring(hostPart.length)
        // "example.com:8080" -> 端口必须紧跟 host，否则已是非法输入
        if (rest.startsWith(":") && !Regex("^:\\d{1,5}(/|\\?|#|$)").containsMatchIn(rest)) {
            return searchBase + encodeQuery(raw)
        }
        val ascii = runCatching { IDN.toASCII(host) }.getOrDefault(host)
        return "https://" + ascii + rest
    }

    /** 与 android.net.Uri.encode 等价的保守实现（JVM 可测，避免隐藏的 Android 依赖）。 */
    fun encodeQuery(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch.isLetterOrDigit() && c < 128 || ch in "-_.!~*'()") sb.append(ch)
            else sb.append('%').append(HEX[c ushr 4]).append(HEX[c and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    fun hostOf(url: String): String = runCatching {
        android.net.Uri.parse(url).host ?: ""
    }.getOrDefault("")

    fun originOf(url: String): String? = runCatching {
        val u = android.net.Uri.parse(url)
        val scheme = u.scheme ?: return null
        val host = u.host ?: return null
        if (scheme != "https" && scheme != "http") return null
        "$scheme://$host" + if (u.port > 0) ":${u.port}" else ""
    }.getOrNull()

    /** 地址栏展示用：去掉 scheme 与末尾斜杠，只留可读部分。 */
    fun displayUrl(url: String): String {
        var s = url
        if (s.startsWith("https://")) s = s.substring(8)
        else if (s.startsWith("http://")) s = s.substring(7)
        if (s.endsWith("/")) s = s.dropLast(1)
        return s
    }

    fun isHttps(url: String) = url.startsWith("https://")
}
