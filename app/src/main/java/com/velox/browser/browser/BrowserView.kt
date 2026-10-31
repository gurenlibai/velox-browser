package com.velox.browser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.velox.browser.metrics.LatencyLogger
import com.velox.browser.prefs.Settings
import com.velox.browser.util.Urls

/**
 * 一个标签页的 WebView 包装。
 *
 * 性能相关的固定配置（每一条都对应 docs/ARCHITECTURE.md §3 的红线）：
 *  - `setRendererPriorityPolicy(IMPORTANT, false)`：不因不可见而降优先级，
 *    否则回切时 renderer 已被系统回收，用户看到白屏（这是 WebView 应用最常见的回切卡顿原因）；
 *  - `LAYER_TYPE_HARDWARE` + 强制硬件加速：走 HWUI 合成路径；
 *  - 关闭 overscroll glow 与滚动条：少两层绘制；
 *  - `offscreenPreRaster = true`：滚动时提前栅格化，减少白块；
 *  - 不在 `onScrollChanged` 里做任何分配（只回调像素值）。
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class BrowserView(context: Context) : FrameLayout(context) {

    val webView: WebView = WebView(context)

    var tabId: Long = -1L
    var isPrivate: Boolean = false

    /** 生命周期回调（由 MainActivity 注入，避免 WebView 直接持有 Activity 引用）。 */
    var onProgress: ((Int) -> Unit)? = null
    var onTitle: ((String) -> Unit)? = null
    var onUrl: ((String) -> Unit)? = null
    var onCommitVisible: (() -> Unit)? = null
    var onPageStartedCb: ((String) -> Unit)? = null
    var onPageFinishedCb: ((String) -> Unit)? = null
    var onNewWindow: ((String) -> Boolean)? = null
    var onRendererGone: ((Boolean) -> Unit)? = null
    var onScroll: ((Int, Int) -> Unit)? = null
    var onShowFileChooser: ((ValueCallback<Array<Uri>>?, WebChromeClient.FileChooserParams?) -> Boolean)? = null

    private var pageStartAt = 0L
    private var cosmeticInjected = false

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.WHITE)
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configure()
    }

    private fun configure() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadsImagesAutomatically = true
        s.blockNetworkImage = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)
        s.textZoom = 100
        s.mediaPlaybackRequiresUserGesture = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(false)
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.setGeolocationEnabled(false)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportMultipleWindows(false)
        s.userAgentString = s.userAgentString
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.safeBrowsingEnabled = true
        }
        // 关键：不允许因不可见而降优先级（回切白屏的主因）
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = OVER_SCROLL_NEVER
        webView.scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_OVERLAY

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    // 阻止重定向到应用商店/第三方 scheme 的静默跳转，交回用户
                    return false
                }
                // 非 http(s)：交给系统（tel/mailto/意向链接），不劫持
                return runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url))
                    true
                }.getOrDefault(true)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!Interceptor.enabled) return null
                return Interceptor.intercept(request)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageStartAt = SystemClock.uptimeMillis()
                cosmeticInjected = false
                LatencyLogger.mark("page.start")
                onPageStartedCb?.invoke(url)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                // 引擎的首个可见帧到达 → 可以撤掉形态快照了
                LatencyLogger.record("page.commitVisible", SystemClock.uptimeMillis() - pageStartAt)
                onCommitVisible?.invoke()
            }

            override fun onPageFinished(view: WebView, url: String) {
                LatencyLogger.record("page.finished", SystemClock.uptimeMillis() - pageStartAt)
                onPageFinishedCb?.invoke(url)
                if (Interceptor.enabled && !cosmeticInjected) {
                    cosmeticInjected = true
                    view.evaluateJavascript(Interceptor.COSMETIC_JS, null)
                }
                // L0：页面稳定后预热可点外链的 origin（成本≈一个握手）
                view.evaluateJavascript(ORIGIN_SAMPLER_JS) { raw ->
                    parseOrigins(raw)?.let { IntentEngine.warmOrigins(isPrivate, it) }
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    Log.w("Velox", "main frame error ${error.errorCode} ${request.url}")
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                // 绝不忽略证书错误
                handler.cancel()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // WebView 只有单个 renderer：崩溃可能带走所有标签页。
                // 交给上层重建，避免整个 App 被杀。
                onRendererGone?.invoke(detail.didCrash())
                return true
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                url?.let { onUrl?.invoke(it) }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                onProgress?.invoke(newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                title?.let { onTitle?.invoke(it) }
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean = onShowFileChooser?.invoke(filePathCallback, fileChooserParams) ?: false

            override fun onPermissionRequest(request: PermissionRequest) {
                // 默认拒绝摄像头/麦克风，需用户在站点级放行（本项目不主动弹窗骚扰）
                request.deny()
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                // 新窗口：取目标 URL 并在本标签页打开，避免后台弹窗
                val href = view.hitTestResult.extra
                return onNewWindow?.invoke(href ?: "") ?: false
            }
        }

        webView.addJavascriptInterface(IntentBridge(), "VeloxIntent")

        webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            onScroll?.invoke(webView.scrollX, scrollY)
        }
    }

    private inner class IntentBridge {
        @JavascriptInterface
        fun onPress(href: String) {
            webView.post {
                IntentEngine.onIntent(webView, href, settings, Interceptor.enabled)
            }
        }

        @JavascriptInterface
        fun onCancel() {
            webView.post { IntentEngine.onIntentCancelled() }
        }
    }

    /** 由 MainActivity 注入，避免跨层引用 */
    lateinit var settings: Settings

    fun load(url: String) {
        if (url.isEmpty()) return
        webView.loadUrl(url)
    }

    fun reload() = webView.reload()
    fun stop() = webView.stopLoading()
    fun canGoBack() = webView.canGoBack()
    fun canGoForward() = webView.canGoForward()
    fun goBack() = webView.goBack()
    fun goForward() = webView.goForward()
    fun url(): String = webView.url ?: ""
    fun title(): String = webView.title ?: ""
    fun scrollY(): Int = webView.scrollY

    fun scrollTo(y: Int) {
        webView.scrollTo(0, y)
    }

    fun injectIntentBridge() {
        webView.evaluateJavascript(IntentEngine.injectionScript("VeloxIntent"), null)
    }

    /** 页面快照：用于标签页缩略图与冷启动首帧（Live Snapshot）。 */
    fun captureSnapshot(maxWidth: Int, onDone: (Bitmap?) -> Unit) {
        webView.postDelayed({
            runCatching {
                val bmp = Bitmap.createBitmap(
                    (maxWidth).coerceAtMost(webView.width.coerceAtLeast(1)),
                    (maxWidth * webView.height / webView.width.coerceAtLeast(1)).coerceAtLeast(1),
                    Bitmap.Config.RGB_565
                )
                val canvas = android.graphics.Canvas(bmp)
                val sx = bmp.width.toFloat() / webView.width.coerceAtLeast(1)
                val sy = bmp.height.toFloat() / webView.height.coerceAtLeast(1)
                canvas.scale(sx, sy)
                webView.draw(canvas)
                onDone(bmp)
            }.onFailure { onDone(null) }
        }, 80)
    }

    fun destroyNow() {
        try {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        } catch (t: Throwable) {
            Log.w("Velox", "destroy webview", t)
        }
    }

    private fun parseOrigins(rawJson: String?): List<String>? {
        if (rawJson.isNullOrBlank() || rawJson == "null") return null
        return runCatching {
            val arr = org.json.JSONArray(rawJson)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
        }.getOrNull()
    }

    companion object {
        /** 采样页面里出现最多的外链 origin（只读 DOM，不修改）。 */
        private const val ORIGIN_SAMPLER_JS = """
(function(){
  try {
    var seen = {}, out = [];
    var links = document.querySelectorAll('a[href^="http"]');
    var n = Math.min(links.length, 400);
    for (var i = 0; i < n; i++) {
      try {
        var o = new URL(links[i].href).origin;
        if (o.indexOf(location.origin) === 0) continue;
        if (seen[o]) { seen[o]++; } else { seen[o] = 1; }
      } catch (e) {}
    }
    Object.keys(seen).sort(function(a,b){return seen[b]-seen[a];}).slice(0,6).forEach(function(o){out.push(o);});
    return JSON.stringify(out);
  } catch (e) { return '[]'; }
})();
"""
    }
}
