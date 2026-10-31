package com.velox.browser

import com.velox.browser.util.Urls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注意：Urls 依赖 android.net.Uri，JVM 单测中只验证不触碰 Uri 的分支
 * （toUrlOrSearch 的 scheme 判断与 host 判定），其余在仪器测试中覆盖。
 */
class UrlsTest {

    private val base = "https://duckduckgo.com/?q="

    @Test
    fun keepsExplicitScheme() {
        assertEquals("https://a.com/x", Urls.toUrlOrSearch("https://a.com/x", base))
        assertEquals("http://localhost:8080", Urls.toUrlOrSearch("http://localhost:8080", base))
    }

    @Test
    fun addsHttpsForBareDomain() {
        assertEquals("https://example.com", Urls.toUrlOrSearch("example.com", base))
        assertEquals("https://example.com/path?q=1", Urls.toUrlOrSearch("example.com/path?q=1", base))
    }

    @Test
    fun localhostUsesHttp() {
        assertEquals("http://localhost/x", Urls.toUrlOrSearch("localhost/x", base))
    }

    @Test
    fun plainWordsBecomeSearch() {
        assertTrue(Urls.toUrlOrSearch("android browser", base).startsWith(base))
        assertTrue(Urls.toUrlOrSearch("velox", base).startsWith(base))
        // 单标签 localhost 走 http，不是搜索
        assertTrue(Urls.toUrlOrSearch("localhost", "https://s/?q=").startsWith("http://localhost"))
        assertEquals("https://duckduckgo.com/?q=android%20browser",
            Urls.toUrlOrSearch("android browser", base))
    }

    @Test
    fun numericTldIsNotHost() {
        assertTrue(Urls.toUrlOrSearch("3.14", base).startsWith(base))
    }

    @Test
    fun httpsDetection() {
        assertTrue(Urls.isHttps("https://a.com"))
        assertFalse(Urls.isHttps("http://a.com"))
    }
}
