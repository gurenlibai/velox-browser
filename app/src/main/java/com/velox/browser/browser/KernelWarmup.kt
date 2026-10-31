package com.velox.browser.browser

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import com.velox.browser.metrics.LatencyLogger
import java.util.concurrent.Executors

/**
 * 引擎预热。研究结论（docs/RESEARCH.md §1.2 / §2.1）：
 * WebView 首次初始化在中端机需 182–400ms，且完全阻塞主线程；第二次仅 ~4ms。
 * 因此把「第一次」提前到用户看不到的地方，是启动体验上最高杠杆的一步。
 *
 * 三层策略，逐层降级，任一层可用即收益：
 *   L1  startUpWebView  —— 把可后台化的初始化搬到后台线程（webkit 1.16 稳定 API）
 *   L2  warmUpRendererProcess —— 预 fork 渲染进程，消掉首导航的进程创建
 *   L3  preconnect      —— 对上次停留站点预建连接（DNS + TCP + TLS/QUIC）
 */
object KernelWarmup {

    private const val TAG = "VeloxKernel"

    @Volatile var engineReadyAtUptime: Long = 0L
        private set
    @Volatile var startupSupported: Boolean = false
        private set
    @Volatile var strategy: String = "none"
        private set

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "velox-kernel-warmup").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    /** 必须在主线程空闲期调用（Application 的 IdleHandler 里）。 */
    fun startAsync(context: Context, warmUrl: String? = null) {
        val app = context.applicationContext
        val t0 = SystemClock.uptimeMillis()
        LatencyLogger.mark("kernel.warmup.begin")

        // 主线程立即返回，重活交给后台线程与 WebView 自身的分片机制
        ioExecutor.execute {
            tryStartUpWebView(app)
            warmRenderer(app)
            warmOrigin(app, warmUrl)
            LatencyLogger.duration("kernel.warmup.total", t0)
        }
    }

    private fun tryStartUpWebView(app: Context) {
        val supported = WebViewFeature.isStartupFeatureSupported(
            app, WebViewFeature.STARTUP_FEATURE_SET_UI_THREAD_STARTUP_MODE_V2
        )
        if (!supported) {
            startupSupported = false
            strategy = "sync-fallback"
            Log.i(TAG, "async startup unsupported on this WebView build")
            return
        }
        startupSupported = true
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "velox-wv-startup").apply { priority = Thread.NORM_PRIORITY }
        }
        try {
            val config = WebViewStartUpConfig.Builder(executor).build()
            // 用带 OutcomeReceiver 的稳定重载（1.16+）
            WebViewCompat.startUpWebView(
                app,
                config,
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) {
                        engineReadyAtUptime = SystemClock.uptimeMillis()
                        strategy = "async-startup"
                        LatencyLogger.record("kernel.engineReady", engineReadyAtUptime - LatencyLogger.since("kernel.warmup.begin"))
                        Log.i(TAG, "engine started asynchronously")
                    }

                    override fun onError(error: WebViewStartupException) {
                        strategy = "sync-fallback"
                        Log.w(TAG, "async startup failed, falling back", error)
                    }
                }
            )
        } catch (t: Throwable) {
            strategy = "sync-fallback"
            Log.w(TAG, "startUpWebView threw, falling back", t)
        }
    }

    private fun warmRenderer(app: Context) {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
            val profile = ProfileStore.getInstance().getOrCreateProfile(KernelProfile.DEFAULT_NAME)
            profile.warmUpRendererProcess()
            strategy += "+renderer"
        } catch (t: Throwable) {
            Log.d(TAG, "renderer warmup unavailable: ${t.javaClass.simpleName}")
        }
    }

    private fun warmOrigin(app: Context, warmUrl: String?) {
        val target = warmUrl ?: return
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
            val origin = runCatching { java.net.URI(target).let { "${it.scheme}://${it.host}" } }.getOrNull()
                ?: return
            val profile = ProfileStore.getInstance().getOrCreateProfile(KernelProfile.DEFAULT_NAME)
            profile.preconnect(origin)
            strategy += "+preconnect"
        } catch (t: Throwable) {
            Log.d(TAG, "preconnect unavailable: ${t.javaClass.simpleName}")
        }
    }

    /** 供 UI 展示的真实能力位（不吹牛，装上什么显示什么）。 */
    fun capabilities(context: Context): String = buildString {
        append("引擎预热: ").append(strategy)
        if (engineReadyAtUptime > 0) append(" (已就绪)")
        append(" · 异步启动: ").append(if (startupSupported) "支持" else "不支持")
        append(" · 多 Profile: ")
            .append(if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) "支持" else "不支持")
        append(" · 预取: ")
            .append(if (KernelProfile.supportsPrefetch()) "支持" else "不支持")
        append(" · 预渲染: ")
            .append(if (KernelProfile.supportsPrerender()) "支持" else "不支持")
        append(" · 第三方 Cookie 策略: ").append(if (Build.VERSION.SDK_INT >= 28) "可配置" else "受限")
        append(" · CookieManager: ").append(if (CookieManager.getInstance() != null) "ok" else "n/a")
    }
}
