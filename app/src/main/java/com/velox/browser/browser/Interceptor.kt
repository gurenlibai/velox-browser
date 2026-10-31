package com.velox.browser.browser

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.velox.browser.filter.BlockList
import java.io.ByteArrayInputStream

/**
 * 请求拦截：在 WebView 的网络回调线程上做零分配判定。
 *
 * 红线（ARCHITECTURE §3）：本类内禁止 IO、正则、日志字符串拼接、锁。
 * 一旦违反，会让该页面**所有**资源请求串行变慢——这是社区里最常见的性能事故。
 */
object Interceptor {

    @Volatile var enabled: Boolean = true

    private val EMPTY = ByteArray(0)
    private val emptyResponse: WebResourceResponse by lazy {
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(EMPTY))
    }

    @Volatile private var blockedCount: Long = 0
    @Volatile private var checkedCount: Long = 0

    fun blocked(): Long = blockedCount
    fun checked(): Long = checkedCount
    fun resetStats() { blockedCount = 0; checkedCount = 0 }

    fun shouldBlock(url: String): Boolean {
        if (!enabled) return false
        val list = BlockList.loaded() ?: return false
        if (list.isEmpty()) return false
        if (list.shouldBlock(url)) { blockedCount++; return true }
        checkedCount++
        return false
    }

    fun intercept(request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null
        if (url.length < 8) return null
        val c = url[0]
        if (c != 'h' && c != 'H') return null
        return if (shouldBlock(url)) emptyResponse else null
    }

    /**
     * 页面级样式修正：消除被拦截后残留的空白广告位与 100vh 抖动。
     * 只在用户开启拦截、且页面已经完成可见渲染后执行一次（见 BrowserView）。
     */
    const val COSMETIC_JS: String = """
(function(){
  if (window.__veloxCosmetic) return; window.__veloxCosmetic = 1;
  var css = 'ins.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],' +
            '.adsbygoogle,[class*="ad-slot"],[class*="advertisement"],' +
            '[data-ad-slot]{display:none!important;height:0!important;margin:0!important;padding:0!important}' +
            'html,body{overscroll-behavior-y:contain}';
  var s = document.createElement('style'); s.textContent = css;
  (document.head || document.documentElement).appendChild(s);
})();
"""
}
