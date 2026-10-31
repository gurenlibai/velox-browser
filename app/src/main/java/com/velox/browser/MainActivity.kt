package com.velox.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.velox.browser.browser.BrowserView
import com.velox.browser.browser.HistoryStore
import com.velox.browser.browser.IntentEngine
import com.velox.browser.browser.Interceptor
import com.velox.browser.browser.KernelProfile
import com.velox.browser.browser.KernelWarmup
import com.velox.browser.browser.TabMeta
import com.velox.browser.browser.TabStore
import com.velox.browser.metrics.LatencyLogger
import com.velox.browser.prefs.Settings
import com.velox.browser.ui.ChromeBar
import com.velox.browser.ui.EdgeGestures
import com.velox.browser.ui.HomeScreen
import com.velox.browser.ui.ListSheet
import com.velox.browser.ui.MenuSheet
import com.velox.browser.ui.SettingsSheet
import com.velox.browser.ui.SnapshotView
import com.velox.browser.ui.TabSwitcher
import com.velox.browser.ui.Theme
import com.velox.browser.util.Urls
import java.io.File

/**
 * 唯一 Activity：所有浏览器 UI 都是叠层，WebView 的几何尺寸在会话内**永不改变**。
 * 这一条消除了「地址栏收起导致 viewport 变化 → 页面重排 → 滚动跳动」这一类浏览器自造 jank。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var app: VeloxApp
    private lateinit var settings: Settings
    private lateinit var tabs: TabStore
    private lateinit var history: HistoryStore
    private lateinit var edge: EdgeGestures

    private lateinit var root: FrameLayout
    private lateinit var contentLayer: FrameLayout
    private lateinit var bar: ChromeBar
    private lateinit var home: HomeScreen
    private lateinit var snapshot: SnapshotView
    private lateinit var tabSwitcher: TabSwitcher
    private lateinit var menuSheet: MenuSheet
    private lateinit var settingsSheet: SettingsSheet
    private lateinit var historySheet: ListSheet
    private lateinit var statsSheet: ListSheet

    private val views = HashMap<Long, BrowserView>()
    private var coldStartBegin = 0L
    private var snapshotShown = false

    /**
     * 冷启动延后创建 WebView。
     *
     * 依据：WebView 首次构造会在**调用线程**同步拉起 Chromium（中端机 182–400ms，
     * Chromium issue 41237034）。若在 onCreate 里 new，则首帧必须等引擎，
     * 「零引擎首帧」的主张就自我作废。因此：
     *   第一帧 = 快照视图 + 工具栏（纯 View 绘制）；
     *   引擎创建 = 首个 Choreographer 帧之后，且优先走后台预热已完成的引擎。
     */
    private var pendingColdUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        coldStartBegin = VeloxApp.appStartUptime
        LatencyLogger.mark("activity.onCreate.begin")
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Theme.INK))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        app = application as VeloxApp
        settings = app.settings
        tabs = app.tabs
        history = HistoryStore(this)

        Interceptor.enabled = settings.adBlockEnabled
        if (tabs.size == 0) tabs.restore()

        buildViews()
        applyInsets()
        registerBackHandler()
        restoreOrCreateTab()

        LatencyLogger.duration("activity.onCreate", LatencyLogger.since("activity.onCreate.begin"))
        // 首帧真正可见后再报告 fully drawn（用于对齐 Macrobenchmark 的 TTFD 口径）
        root.post { reportFullyDrawn() }
        LatencyLogger.record("startup.toFirstFrame", SystemClock.uptimeMillis() - coldStartBegin)
    }

    // ---------------------------------------------------------------- 视图搭建

    private fun buildViews() {
        root = FrameLayout(this)
        root.setBackgroundColor(Theme.INK)

        contentLayer = FrameLayout(this)
        root.addView(contentLayer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        snapshot = SnapshotView(this)
        contentLayer.addView(snapshot, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        home = HomeScreen(this)
        home.visibility = View.GONE
        home.actions = object : HomeScreen.Actions {
            override fun onSearch(query: String) {
                openUrl(Urls.toUrlOrSearch(query, Settings.searchUrl(settings.searchEngine)))
            }
            override fun onOpen(url: String) = openUrl(url)
            override fun onQuickAccessLongPress(url: String) {
                Toast.makeText(this@MainActivity, Urls.displayUrl(url), Toast.LENGTH_SHORT).show()
            }
        }
        contentLayer.addView(home, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        bar = ChromeBar(this)
        bar.listener = object : ChromeBar.Listener {
            override fun onBack() { if (current()?.canGoBack() == true) current()?.goBack() else finishToHome() }
            override fun onForward() { current()?.goForward() }
            override fun onReload() { current()?.let { if (it.webView.progress < 100) it.stop() else it.reload() } }
            override fun onOpenTabs() { showTabs() }
            override fun onOpenMenu() { showMenu() }
            override fun onOmniboxTap() { editCurrentUrl() }
            override fun onModeChanged(mode: ChromeBar.Mode) {
                settings.toolbarMode = when (mode) {
                    ChromeBar.Mode.PINNED -> Settings.MODE_PINNED
                    ChromeBar.Mode.COLLAPSED -> Settings.MODE_COLLAPSED
                    ChromeBar.Mode.HIDDEN -> Settings.MODE_HIDDEN
                }
            }
        }
        val barParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        barParams.setMargins(Theme.dp(this, 8f), 0, Theme.dp(this, 8f), Theme.dp(this, 6f))
        root.addView(bar, barParams)

        edge = EdgeGestures(this)
        edge.callback = object : EdgeGestures.Callback {
            override fun onBackPeek(progress: Float) {
                val v = current() ?: return
                v.translationX = (progress.coerceIn(0f, 1f) * contentLayer.width * 0.22f)
            }
            override fun onBackCommit() {
                val v = current() ?: return
                v.animate().translationX(0f).setDuration(120).start()
                if (v.canGoBack()) v.goBack() else finishToHome()
            }
            override fun onBackCancel() {
                current()?.animate()?.translationX(0f)?.setDuration(140)
                    ?.setInterpolator(PathInterpolator(0.2f, 0.9f, 0.1f, 1f))?.start()
            }
            override fun onForwardCommit() {
                val v = current() ?: return
                v.animate().translationX(0f).setDuration(120).start()
                v.goForward()
            }
            override fun onPullToRefresh() { current()?.reload() }
            override fun onSwipeUpOnBar() { bar.setMode(ChromeBar.Mode.HIDDEN) }
            override fun isAtTop(): Boolean = (current()?.scrollY() ?: 0) <= 0
        }

        tabSwitcher = TabSwitcher(this)
        tabSwitcher.listener = object : TabSwitcher.Handler {
            override fun onSelect(id: Long) = selectTab(id)
            override fun onClose(id: Long) = closeTab(id)
            override fun onNewTab() = newTab()
            override fun onCloseAll() {
                tabs.closeAll()
                views.values.forEach { it.destroyNow() }
                views.clear()
                showHome()
            }
        }

        menuSheet = MenuSheet(this)
        menuSheet.adBlockOn = settings.adBlockEnabled
        menuSheet.actions = object : MenuSheet.Actions {
            override fun newTab() { this@MainActivity.newTab(null, false) }
            override fun newPrivateTab() { this@MainActivity.newTab(null, true) }
            override fun openTabs() { showTabs() }
            override fun history() { showHistory() }
            override fun downloads() {
                Toast.makeText(this@MainActivity, "下载由系统通知栏接管", Toast.LENGTH_SHORT).show()
            }
            override fun toggleDesktop() { toggleDesktopMode() }
            override fun toggleReader() { toggleReaderMode() }
            override fun sharePage() { shareCurrent() }
            override fun findInPage() { this@MainActivity.findInPage() }
            override fun toggleAdBlock() {
                settings.adBlockEnabled = !settings.adBlockEnabled
                Interceptor.enabled = settings.adBlockEnabled
                current()?.reload()
            }
            override fun settings() { showSettings() }
            override fun about() { showAbout() }
        }

        settingsSheet = SettingsSheet(this)
        settingsSheet.settings = settings
        settingsSheet.listener = object : SettingsSheet.Handler {
            override fun onChanged() = applySettings()
            override fun onOpenStats() { settingsSheet.dismiss(); showStats() }
            override fun onClearData() { clearData() }
        }

        historySheet = ListSheet(this, "历史记录")
        historySheet.onPick = { url -> openUrl(url) }

        statsSheet = ListSheet(this, "本机性能采样")

        root.addView(tabSwitcher, fullscreen())
        root.addView(menuSheet, fullscreen())
        root.addView(settingsSheet, fullscreen())
        root.addView(historySheet, fullscreen())
        root.addView(statsSheet, fullscreen())

        setContentView(root)

        // 手势层挂在内容区之上、工具栏之下：只在其自身被触摸时介入
        contentLayer.setOnTouchListener { _, event -> edge.onTouchEvent(event, contentLayer.width, contentLayer.height) }
    }

    private fun fullscreen() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )

    @SuppressLint("ClickableViewAccessibility")
    private fun applyInsets() {
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            (bar.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom + Theme.dp(this, 6f)
            bar.requestLayout()
            insets
        }
    }

    // ---------------------------------------------------------------- 标签页

    private fun restoreOrCreateTab() {
        val current = tabs.current
        if (current == null) {
            showHome()
            // 没有会话：等首帧画完再建 WebView，保证首帧不等引擎
            deferEngine { newTab(startUrl = null) }
        } else {
            tabs.select(current.id)
            if (current.url.isNotBlank()) {
                showSnapshotThenLoad(current)
                return
            }
            showHome()
            deferEngine { tabs.current?.let { obtainView(it) } }
        }
    }

    /** 把重活推到「首帧已经上屏之后」。 */
    private fun deferEngine(block: () -> Unit) {
        root.post {
            android.view.Choreographer.getInstance().postFrameCallback {
                block()
                LatencyLogger.record("startup.engineCreatedAt", SystemClock.uptimeMillis() - coldStartBegin)
            }
        }
    }

    private fun showSnapshotThenLoad(tab: TabMeta) {
        snapshotShown = true
        pendingColdUrl = tab.url
        bar.setUrl(tab.url, Urls.isHttps(tab.url), Urls.hostOf(tab.url))
        bar.setTabCount(tabs.size)
        // 同步命中内存快照 → 首帧就是上次的画面，不触碰引擎
        val cached = app.snapshots.peek(tab.id)
        if (cached != null) {
            snapshot.setSnapshot(cached)
            LatencyLogger.record("startup.snapshotVisible", SystemClock.uptimeMillis() - coldStartBegin)
            deferEngine { attachColdTab(tab) }
        } else {
            // 异步读盘（WebP 解码），首帧仍然只是一次绘制
            app.snapshots.load(tab.id) { bmp ->
                if (isFinishing || isDestroyed) return@load
                if (bmp != null) {
                    snapshot.setSnapshot(bmp)
                    LatencyLogger.record("startup.snapshotVisible", SystemClock.uptimeMillis() - coldStartBegin)
                }
            }
            deferEngine { attachColdTab(tab) }
        }
    }

    /** 首帧之后：创建/取出该标签页的 WebView，并补上未完成的导航。 */
    private fun attachColdTab(tab: TabMeta) {
        if (isFinishing || isDestroyed) return
        val meta = tabs.all().firstOrNull { it.id == tab.id } ?: return
        val v = obtainView(meta)
        attachView(v)
        // 冷启动时快照盖在最上层，交给 onPageCommitVisible 淡出
        pendingColdUrl?.let { url ->
            pendingColdUrl = null
            v.load(url)
        }
    }

    private fun newTab(startUrl: String? = null, private: Boolean = false) {
        val tab = tabs.newTab(url = startUrl ?: "", private = private)
        selectTab(tab.id, loadUrl = startUrl)
    }

    private fun selectTab(id: Long, loadUrl: String? = null) {
        tabs.select(id) ?: return
        // 选中的标签页必须可见（可能刚刚被预算淘汰过）
        val meta = tabs.current ?: return
        val view = obtainView(meta)
        attachView(view)
        bar.setTabCount(tabs.size)
        bar.setUrl(meta.url, Urls.isHttps(meta.url), Urls.hostOf(meta.url))
        home.visibility = View.GONE
        enforceBudget()
        if (loadUrl != null) view.load(loadUrl) else if (meta.url.isBlank()) showHome()
    }

    private fun obtainView(meta: TabMeta): BrowserView {
        views[meta.id]?.let { return it }
        val view = BrowserView(this)
        view.tabId = meta.id
        view.isPrivate = meta.private
        view.settings = settings
        KernelProfile.attach(view.webView, meta.private)
        applyWebSettings(view)
        wireBrowserView(view)
        views[meta.id] = view
        return view
    }

    private fun attachView(view: BrowserView) {
        if (view.parent === contentLayer) return
        (view.parent as? ViewGroup)?.removeView(view)
        contentLayer.addView(view, 0, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        // 其余标签页隐藏而非销毁，保持滚动位置；受预算限制
        views.values.forEach { v -> if (v !== view) v.visibility = View.GONE }
        view.visibility = View.VISIBLE
    }

    private fun wireBrowserView(view: BrowserView) {
        view.onProgress = { p ->
            bar.setProgress(p)
            bar.setLoading(p < 100)
            bar.setBlockedCount(Interceptor.blocked())
        }
        view.onTitle = { title ->
            tabs.update(view.tabId) { it.title = title }
        }
        view.onUrl = { url ->
            tabs.update(view.tabId) { it.url = url }
            bar.setUrl(url, Urls.isHttps(url), Urls.hostOf(url))
            bar.setNavState(view.canGoBack(), view.canGoForward())
            settings.lastClosedUrl = url
        }
        view.onPageStartedCb = { url ->
            tabs.update(view.tabId) { it.url = url }
            bar.setNavState(view.canGoBack(), view.canGoForward())
            IntentEngine.onNavigated(url)
        }
        view.onCommitVisible = {
            if (snapshotShown) { snapshot.fadeOut(160); snapshotShown = false }
        }
        view.onPageFinishedCb = { url ->
            tabs.update(view.tabId) { it.url = url; it.title = view.title() }
            history.record(url, view.title())
            bar.setNavState(view.canGoBack(), view.canGoForward())
            // Chromium 在 addJavascriptInterface 时对对象做快照，
            // 页面导航后旧对象失效 → 每次导航完成都重新注入桥与命中脚本。
            view.refreshJsBridge()
            bar.setBlockedCount(Interceptor.blocked())
        }
        view.onRendererGone = { crashed ->
            onRendererGone(crashed)
        }
        view.onScroll = { _, y ->
            // 只记录滚动位置（用于会话恢复）；不做任何分配，也不在滚动中改动工具栏，
            // 避免「滚动时 UI 抖动」——这是本项目的硬性红线之一。
            tabs.update(view.tabId) { it.scrollY = y }
        }
        view.onNewWindow = { url ->
            if (url.isBlank()) {
                false
            } else {
                newTab(startUrl = url, private = view.isPrivate)
                true
            }
        }
        view.onDownload = { url, ua, disposition, mime ->
            startDownload(url, ua, disposition, mime)
        }
        view.onShowFileChooser = { _, _ ->
            Toast.makeText(this, "文件上传暂未开放", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun current(): BrowserView? = tabs.current?.let { views[it.id] }

    private fun closeTab(id: Long) {
        views.remove(id)?.let { v ->
            v.captureSnapshot(360) { bmp -> if (bmp != null) app.snapshots.save(id, bmp, v.isPrivate) }
            v.destroyNow()
        }
        val idx = tabs.close(id)
        if (idx < 0) {
            showHome()
        } else {
            tabs.current?.let { selectTab(it.id) }
        }
        bar.setTabCount(tabs.size)
    }

    /** 存活视图预算：超出时淘汰最久未用且非当前的标签页（预测式淘汰）。 */
    private fun enforceBudget() {
        val budget = tabs.liveTabBudget()
        if (views.size <= budget) return
        val keep = HashSet<Long>()
        tabs.current?.let { keep.add(it.id) }
        val now = SystemClock.elapsedRealtime()
        val candidates = views.keys
            .filter { it !in keep }
            .mapNotNull { id -> tabs.all().firstOrNull { it.id == id }?.let { id to tabs.evictionScore(it, now) } }
            .sortedBy { it.second }
        for ((id, _) in candidates) {
            if (views.size <= budget) break
            val v = views.remove(id) ?: continue
            v.captureSnapshot(300) { bmp -> if (bmp != null) app.snapshots.save(id, bmp, v.isPrivate) }
            v.destroyNow()
        }
    }

    // ---------------------------------------------------------------- 导航

    private fun openUrl(url: String) {
        val meta = tabs.current
        if (meta == null) { newTab(startUrl = url); return }
        tabs.update(meta.id) { it.url = url }
        val v = current() ?: obtainView(tabs.current!!)
        attachView(v)
        home.visibility = View.GONE
        v.load(url)
        bar.setUrl(url, Urls.isHttps(url), Urls.hostOf(url))
    }

    private fun showHome() {
        views.values.forEach { it.visibility = View.GONE }
        home.visibility = View.VISIBLE
        bar.setUrl("", false, "")
        bar.setNavState(false, false)
        refreshHome()
    }

    private fun finishToHome() {
        if (views.isEmpty()) { finish(); return }
        showHome()
    }

    private fun refreshHome() {
        history.topSites(8) { top ->
            home.setQuickAccess(top.map { it.url to (it.title.ifBlank { Urls.hostOf(it.url) }) })
        }
        history.recent(4) { recent ->
            home.setRecent(recent.map { Triple(it.url, it.title.ifBlank { Urls.hostOf(it.url) }, it.at) })
        }
    }

    private fun toggleDesktopMode() {
        current()?.let { v ->
            val ua = v.webView.settings.userAgentString
            v.webView.settings.userAgentString =
                if (ua.contains("Mobile")) ua.replace("Mobile", "Desktop") else ua.replace("Desktop", "Mobile")
            v.reload()
        }
    }

    /** 阅读模式：极简的启发式正文抽取，就地重建（不依赖任何第三方库）。 */
    private fun toggleReaderMode() {
        val v = current() ?: return
        v.webView.evaluateJavascript(READER_JS) { }
    }

    private fun shareCurrent() {
        val v = current() ?: return
        val url = v.url().ifBlank { tabs.current?.url ?: "" }
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_TITLE, v.title())
        }
        startActivity(Intent.createChooser(intent, "分享"))
    }

    private fun findInPage() {
        val v = current() ?: return
        val input = android.widget.EditText(this).apply {
            hint = "页内查找"
            setSingleLine()
            setTextColor(Theme.TEXT_ON_DARK)
            setHintTextColor(Theme.TEXT_TERTIARY)
            setPadding(Theme.dp(this@MainActivity, 16f), Theme.dp(this@MainActivity, 10f),
                Theme.dp(this@MainActivity, 16f), Theme.dp(this@MainActivity, 10f))
        }
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setTitle("页内查找")
            .setView(input)
            .setPositiveButton("查找") { _, _ ->
                v.webView.findAllAsync(input.text.toString())
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    private fun editCurrentUrl() {
        val v = current()
        val currentUrl = v?.url() ?: ""
        val input = android.widget.EditText(this).apply {
            setText(Urls.displayUrl(currentUrl))
            setSelectAllOnFocus(true)
            setTextColor(Theme.TEXT_ON_DARK)
            setHintTextColor(Theme.TEXT_TERTIARY)
            hint = "搜索或输入网址"
            setPadding(Theme.dp(this@MainActivity, 16f), Theme.dp(this@MainActivity, 10f),
                Theme.dp(this@MainActivity, 16f), Theme.dp(this@MainActivity, 10f))
        }
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setTitle("地址")
            .setView(input)
            .setPositiveButton("前往") { _, _ ->
                val q = input.text.toString()
                openUrl(Urls.toUrlOrSearch(q, Settings.searchUrl(settings.searchEngine)))
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener { input.requestFocus() }
        dialog.show()
    }

    /** 下载：交给系统 DownloadManager（可见通知、断点续传、不占用浏览器进程）。 */
    private fun startDownload(url: String, userAgent: String, disposition: String?, mime: String?) {
        try {
            val request = android.app.DownloadManager.Request(android.net.Uri.parse(url)).apply {
                setMimeType(mime)
                addRequestHeader("User-Agent", userAgent)
                addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                if (disposition != null) addRequestHeader("Content-Disposition", disposition)
                setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
            }
            val dm = getSystemService(android.app.DownloadManager::class.java)
            dm.enqueue(request)
            Toast.makeText(this, "已加入下载队列", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "下载失败：${t.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- 面板

    private fun showTabs() {
        tabSwitcher.bind(tabs.all(), tabs.current?.id) { id -> app.snapshots.peek(id) }
        tabSwitcher.show()
    }

    private fun showMenu() {
        menuSheet.adBlockOn = settings.adBlockEnabled
        menuSheet.refresh()
        menuSheet.show()
    }

    private fun showSettings() {
        settingsSheet.capabilities = KernelWarmup.capabilities(this)
        settingsSheet.tabBudget = tabs.liveTabBudget()
        settingsSheet.bind()
        settingsSheet.show()
    }

    private fun showHistory() {
        history.recent(80) { items ->
            historySheet.setItems(items.map { v ->
                Triple(v.title.ifBlank { Urls.displayUrl(v.url) }, Urls.displayUrl(v.url), v.url)
            }, emptyText = "还没有历史记录")
            historySheet.show()
        }
    }

    private fun showStats() {
        val sb = StringBuilder()
        sb.appendLine(KernelWarmup.capabilities(this))
        sb.appendLine()
        sb.appendLine("拦截: 已拦 ${Interceptor.blocked()} / 判定 ${Interceptor.checked()}")
        sb.appendLine("规则集: ${com.velox.browser.filter.BlockList.get(this).size} 条域名")
        sb.appendLine("标签页: ${tabs.size} 个 / 活体 ${views.size} 个 (预算 ${tabs.liveTabBudget()})")
        sb.appendLine(IntentEngine.stats())
        sb.appendLine("快照占用: ${app.snapshots.diskUsageBytes() / 1024} KB")
        sb.appendLine()
        sb.append(LatencyLogger.dump())
        statsSheet.setText(sb.toString())
        statsSheet.show()
    }

    private fun showAbout() {
        val dlg = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setTitle("Velox 1.0.0")
            .setMessage(
                "一个把「时间差」做到极致的安卓浏览器。\n\n" +
                    "· 引擎异步预热 + 无引擎首帧快照\n" +
                    "· 手指按下即预测目标并预取/预渲染\n" +
                    "· 内核级广告与追踪拦截（10 万+ 域名，零阻塞判定）\n" +
                    "· 工具栏三态叠层，永不改变页面几何尺寸\n" +
                    "· 无账号、无云同步、无遥测\n\n" +
                    "引擎：系统 WebView（Chromium），与 Chrome 同源。\n" +
                    "本应用不宣称自研内核；优势来自时序编排与交互设计，详见项目 docs/。"
            )
            .setPositiveButton("知道了", null)
            .create()
        dlg.show()
    }

    private fun clearData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        history.clear()
        app.snapshots.clearAll()
        tabs.closeAll()
        views.values.forEach { it.destroyNow() }
        views.clear()
        if (Build.VERSION.SDK_INT >= 26) {
            WebView(this).clearCache(true)
        }
        Toast.makeText(this, "已清除本地数据", Toast.LENGTH_SHORT).show()
        showHome()
    }

    // ---------------------------------------------------------------- 设置应用

    private fun applySettings() {
        Interceptor.enabled = settings.adBlockEnabled
        views.values.forEach { applyWebSettings(it) }
        bar.setMode(when (settings.toolbarMode) {
            Settings.MODE_COLLAPSED -> ChromeBar.Mode.COLLAPSED
            Settings.MODE_HIDDEN -> ChromeBar.Mode.HIDDEN
            else -> ChromeBar.Mode.PINNED
        })
    }

    private fun applyWebSettings(view: BrowserView) {
        val s = view.webView.settings
        s.javaScriptEnabled = settings.javascriptEnabled
        s.domStorageEnabled = settings.javascriptEnabled
        CookieManager.getInstance().setAcceptThirdPartyCookies(view.webView, !settings.blockThirdPartyCookies)
        view.webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
    }

    // ---------------------------------------------------------------- 生命周期

    private fun registerBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    menuSheet.visibility == View.VISIBLE -> menuSheet.dismiss()
                    settingsSheet.visibility == View.VISIBLE -> settingsSheet.dismiss()
                    tabSwitcher.visibility == View.VISIBLE -> tabSwitcher.dismiss()
                    historySheet.visibility == View.VISIBLE -> historySheet.dismiss()
                    statsSheet.visibility == View.VISIBLE -> statsSheet.dismiss()
                    current()?.canGoBack() == true -> current()?.goBack()
                    bar.mode == ChromeBar.Mode.HIDDEN -> bar.setMode(ChromeBar.Mode.PINNED)
                    else -> finishToHome()
                }
            }
        })
    }

    /** WebView 单 renderer 崩溃的恢复路径：清空所有视图，重建当前标签页。 */
    private fun onRendererGone(crashed: Boolean) {
        views.values.forEach { runCatching { it.destroyNow() } }
        views.clear()
        val meta = tabs.current ?: return
        val v = obtainView(meta)
        attachView(v)
        if (meta.url.isNotBlank()) v.load(meta.url)
        Toast.makeText(this, if (crashed) "渲染进程已恢复" else "渲染进程被系统回收，已重建", Toast.LENGTH_SHORT).show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { openUrl(it) }
    }

    private var snapshotOnPause: Bitmap? = null

    override fun onPause() {
        super.onPause()
        val meta = tabs.current
        if (meta != null && meta.url.isNotBlank()) {
            settings.lastClosedUrl = meta.url
            meta.scrollY = current()?.scrollY() ?: meta.scrollY
            current()?.captureSnapshot(420) { bmp ->
                if (bmp != null) app.snapshots.save(meta.id, bmp, meta.private)
            }
        }
        tabs.persist()
        current()?.webView?.onPause()
        current()?.webView?.pauseTimers()
    }

    override fun onResume() {
        super.onResume()
        current()?.webView?.resumeTimers()
        current()?.webView?.onResume()
    }

    override fun onDestroy() {
        views.values.forEach { runCatching { it.destroyNow() } }
        views.clear()
        if (tabs.isPrivateSession()) KernelProfile.destroyPrivate()
        super.onDestroy()
    }

    companion object {
        private const val READER_JS = """
(function(){
  if (window.__veloxReader) { document.documentElement.classList.remove('velox-reader'); window.__veloxReader = 0; return; }
  window.__veloxReader = 1;
  var css = 'html.velox-reader body{max-width:44rem;margin:0 auto;padding:24px 18px 40vh;' +
            'font:18px/1.75 -apple-system,system-ui,sans-serif;color:#111} ' +
            'html.velox-reader img,html.velox-reader video{max-width:100%;height:auto} ' +
            'html.velox-reader nav,html.velox-reader aside,html.velox-reader footer,' +
            'html.velox-reader [role=banner],html.velox-reader [class*=ad]{display:none!important}';
  var s = document.getElementById('velox-reader-css');
  if (!s) { s = document.createElement('style'); s.id = 'velox-reader-css'; s.textContent = css; document.head.appendChild(s); }
  document.documentElement.classList.add('velox-reader');
})();
"""
    }
}
