package com.velox.browser.browser

import android.os.CancellationSignal
import android.webkit.WebView
import androidx.webkit.PrefetchException
import androidx.webkit.PrerenderException
import androidx.webkit.PrerenderOperationCallback
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewOutcomeReceiver
import java.util.concurrent.Executor

/**
 * 浏览会话隔离：默认会话 / 无痕会话各用一个 WebView Profile。
 *
 * 为什么用 Profile 而不是「切 Activity + clearHistory」：
 *  - Profile 级隔离保证 Cookie / LocalStorage / ServiceWorker 不串；
 *  - 退出无痕时 deleteProfile 即可彻底销毁，无需遍历清理；
 *  - MULTI_PROFILE 不可用的旧内核上，退化为「默认 Profile + 退出时清理清单」。
 */
object KernelProfile {

    const val DEFAULT_NAME = "velox.default"
    const val PRIVATE_NAME = "velox.private"

    private val directExecutor = Executor { it.run() }

    fun attach(webView: WebView, private: Boolean): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return false
        return try {
            val profile = ProfileStore.getInstance().getOrCreateProfile(name(private))
            WebViewCompat.setProfile(webView, profile.name)
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun name(private: Boolean) = if (private) PRIVATE_NAME else DEFAULT_NAME

    fun profileFor(private: Boolean): Profile? = try {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) null
        else ProfileStore.getInstance().getOrCreateProfile(name(private))
    } catch (t: Throwable) {
        null
    }

    fun destroyPrivate() {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().deleteProfile(PRIVATE_NAME)
            }
        } catch (t: Throwable) {
            // 忽略：下次启动会重建
        }
    }

    /**
     * 预连接：只花一个握手，收益是省掉 DNS + TCP + TLS 的 1~3 个 RTT。
     * 这是 L0 级信号（无需用户任何动作）最划算的动作。
     */
    fun preconnect(origin: String, private: Boolean): Boolean {
        val profile = profileFor(private) ?: return false
        return try {
            profile.preconnect(origin)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** L1：预取文档（只取 HTML，共享缓存，代价中等）。 */
    fun prefetch(url: String, private: Boolean, signal: CancellationSignal? = null): Boolean {
        val profile = profileFor(private) ?: return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROFILE_URL_PREFETCH)) return false
        return try {
            profile.prefetchUrlAsync(
                url, signal, directExecutor,
                object : WebViewOutcomeReceiver<Void?, PrefetchException> {
                    override fun onResult(result: Void?) = Unit
                    override fun onError(error: PrefetchException) = Unit
                }
            )
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** L2：预渲染整页（代价最高，只给「手指已按下」这类高置信度目标）。 */
    fun prerender(webView: WebView, url: String, signal: CancellationSignal? = null): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PRERENDER_WITH_URL)) return false
        return try {
            WebViewCompat.prerenderUrlAsync(
                webView, url, signal, directExecutor,
                object : PrerenderOperationCallback {
                    override fun onPrerenderActivated() = Unit
                    override fun onError(exception: PrerenderException) = Unit
                }
            )
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun supportsPrefetch() = WebViewFeature.isFeatureSupported(WebViewFeature.PROFILE_URL_PREFETCH)
    fun supportsPrerender() = WebViewFeature.isFeatureSupported(WebViewFeature.PRERENDER_WITH_URL)
    fun supportsProfiles() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
}
