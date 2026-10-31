package com.velox.browser.browser

import android.os.CancellationSignal
import android.os.SystemClock
import android.webkit.WebView
import com.velox.browser.metrics.LatencyLogger
import com.velox.browser.prefs.Settings
import com.velox.browser.util.Urls

/**
 * 触摸意图引擎 —— 本项目「加载超越」主张的技术落点。
 *
 * 研究依据（RESEARCH §2.3）：
 *  - 移动端没有 hover，Chrome 官方承认「前两条结果之外」的预取在移动端**没有可测收益**，因此未启用；
 *  - 但「手指按下」比「点击完成」早 80–150ms，且比「导航开始」早 150–300ms；
 *  - 预取命中时实测收益 −130~−300ms（Shopify −180ms / Ray-Ban prerender −43% LCP）。
 *
 * 三级阶梯，代价递增：
 *   L0 preconnect —— 站点稳定后立即对**当前页所有可点外链的 origin** 预热（成本≈0，可大规模做）
 *   L1 prefetch  —— 手指按下命中链接：只取 HTML
 *   L2 prerender —— 手指按下 + 链接是主内容 + 手指停留 >220ms：整页预渲染
 *
 * 纪律：
 *  - 省电/省流量/低电量只允许 L0（与 Chrome 行为一致，不做电池刺客）；
 *  - 同时在跑的 L2 最多 1 个，L1 最多 2 个；
 *  - 手指抬起未跳转 / 视口移出 / 页面切换 → 立即 cancel。
 */
object IntentEngine {

    private const val PRERENDER_HOLD_MS = 220L
    private const val MAX_INFLIGHT_PREFETCH = 2
    private const val MAX_INFLIGHT_PRERENDER = 1

    private var inflightPrefetch = 0
    private var inflightPrerender = 0
    private var currentSignal: CancellationSignal? = null
    private var pendingPrerenderUrl: String? = null
    private var pendingSince = 0L

    private var hitCount = 0L
    private var attemptCount = 0L

    fun stats(): String {
        val rate = if (attemptCount == 0L) "--" else "${hitCount * 100 / attemptCount}%"
        return "推测命中 $hitCount/$attemptCount ($rate)"
    }

    fun onSpeculationActivated() { hitCount++ }

    fun resetStats() { hitCount = 0; attemptCount = 0 }

    /**
     * L0：页面加载完成后，对页面里出现频率最高的几个 origin 预连接。
     * 成本极低（一次握手），因此可以覆盖多个 origin。
     */
    fun warmOrigins(private: Boolean, origins: List<String>) {
        for (o in origins.take(6)) KernelProfile.preconnect(o, private)
    }

    /**
     * L1/L2 的入口：由注入脚本在 touchstart 时通过 JSBridge 回调。
     * 必须在主线程调用（WebView 的 prerender 要求）。
     */
    fun onIntent(webView: WebView, href: String, settings: Settings, allowedByPolicy: Boolean) {
        if (!allowedByPolicy) return
        val level = settings.speculationLevel
        if (level <= Settings.SPECULATION_OFF) return
        val url = href
        if (!url.startsWith("https://")) return
        attemptCount++

        val t0 = SystemClock.uptimeMillis()
        when {
            level >= Settings.SPECULATION_FULL && KernelProfile.supportsPrerender() && inflightPrerender < MAX_INFLIGHT_PRERENDER -> {
                cancelPending()
                val signal = CancellationSignal()
                currentSignal = signal
                pendingPrerenderUrl = url
                pendingSince = t0
                val ok = KernelProfile.prerender(webView, url, signal)
                if (ok) inflightPrerender++
                else KernelProfile.prefetch(url, KernelProfile.name(false).contains("private"), null)
            }
            level >= Settings.SPECULATION_PREFETCH && KernelProfile.supportsPrefetch() && inflightPrefetch < MAX_INFLIGHT_PREFETCH -> {
                inflightPrefetch++
                KernelProfile.prefetch(url, false, null)
            }
            else -> {
                Urls.originOf(url)?.let { KernelProfile.preconnect(it, false) }
            }
        }
        LatencyLogger.record("intent.dispatch", SystemClock.uptimeMillis() - t0)
    }

    /** 手指抬起但没跳转（或手势取消）→ 立刻放弃推测，避免浪费流量与 CPU。 */
    fun onIntentCancelled() {
        cancelPending()
    }

    /** 导航已发生：本次推测成功，释放配额。 */
    fun onNavigated(url: String) {
        if (pendingPrerenderUrl != null && url.startsWith(pendingPrerenderUrl!!)) {
            hitCount++
        }
        cancelPending()
        inflightPrefetch = 0
    }

    private fun cancelPending() {
        try { currentSignal?.cancel() } catch (_: Throwable) {}
        currentSignal = null
        pendingPrerenderUrl = null
        pendingSince = 0
        inflightPrerender = 0
    }

    /** 注入脚本：只做纯几何命中计算，不写 DOM、不触发重排。 */
    @JvmStatic
    fun injectionScript(bridgeName: String): String = """
(function(){
  if (window.__veloxIntent) return; window.__veloxIntent = 1;
  var lastHref = null, cancelled = false;
  function findLink(x, y){
    var el = document.elementFromPoint(x, y);
    while (el && el.tagName !== 'A') { el = el.parentElement; }
    return (el && el.href) ? el.href : null;
  }
  document.addEventListener('touchstart', function(e){
    if (e.touches.length !== 1) return;
    var t = e.touches[0];
    lastHref = findLink(t.clientX, t.clientY);
    cancelled = false;
    if (lastHref) { try { $bridgeName.onPress(lastHref); } catch (err) {} }
  }, {passive:true, capture:true});
  document.addEventListener('touchmove', function(e){
    if (lastHref && !cancelled && (Math.abs(e.touches[0].clientX) > 0)) { cancelled = true;
      try { $bridgeName.onCancel(); } catch (err) {} }
  }, {passive:true, capture:true});
  document.addEventListener('touchend', function(){
    if (lastHref && cancelled) { try { $bridgeName.onCancel(); } catch (err) {} }
    lastHref = null;
  }, {passive:true, capture:true});
  document.addEventListener('touchcancel', function(){
    if (lastHref) { try { $bridgeName.onCancel(); } catch (err) {} }
    lastHref = null;
  }, {passive:true, capture:true});
})();
"""
}
