package com.velox.browser.browser

import android.content.Context
import android.os.SystemClock
import com.velox.browser.prefs.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

data class TabMeta(
    val id: Long,
    var url: String = "",
    var title: String = "",
    var private: Boolean = false,
    var lastAccess: Long = SystemClock.elapsedRealtime(),
    var scrollY: Int = 0,
    var hasMedia: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("title", title)
        put("private", private); put("lastAccess", lastAccess)
        put("scrollY", scrollY); put("hasMedia", hasMedia)
    }

    companion object {
        fun fromJson(o: JSONObject) = TabMeta(
            id = o.optLong("id"),
            url = o.optString("url"),
            title = o.optString("title"),
            private = o.optBoolean("private"),
            lastAccess = o.optLong("lastAccess"),
            scrollY = o.optInt("scrollY"),
            hasMedia = o.optBoolean("hasMedia")
        )
    }
}

/**
 * 标签页元数据与会话。
 *
 * 设计取舍（RESEARCH §2.4）：不追求「全部标签页常驻引擎」。
 * WebView 全 App 只有一个 renderer，常驻 WebView 越多，PSS 越高、GC 压力越大、
 * 主线程动画越容易掉帧。因此：
 *   - 仅保留少量「活」WebView（默认 3，按 RAM 调整）；
 *   - 其余标签页保留元数据 + 快照，回切时先出快照再加载，用户看不到白屏；
 *   - 正在播放媒体 / 有表单输入的标签页受保护，不会被优先淘汰。
 */
class TabStore(private val context: Context, private val settings: Settings) {

    private val ids = AtomicLong(System.currentTimeMillis())
    private val tabs = ArrayList<TabMeta>()
    private var currentIndex = -1

    val size: Int get() = tabs.size
    val current: TabMeta? get() = tabs.getOrNull(currentIndex)
    fun all(): List<TabMeta> = tabs
    fun indexOf(id: Long): Int = tabs.indexOfFirst { it.id == id }

    fun newTab(url: String = "", private: Boolean = false, select: Boolean = true): TabMeta {
        val tab = TabMeta(id = ids.incrementAndGet(), url = url, private = private)
        tabs.add(tab)
        if (select) { currentIndex = tabs.size - 1; tab.lastAccess = SystemClock.elapsedRealtime() }
        return tab
    }

    fun select(id: Long): TabMeta? {
        val i = indexOf(id)
        if (i < 0) return null
        currentIndex = i
        tabs[i].lastAccess = SystemClock.elapsedRealtime()
        return tabs[i]
    }

    fun close(id: Long): Int {
        val i = indexOf(id)
        if (i < 0) return currentIndex
        tabs.removeAt(i)
        if (tabs.isEmpty()) currentIndex = -1
        else currentIndex = when {
            i < currentIndex -> currentIndex - 1
            i == currentIndex -> i.coerceAtMost(tabs.size - 1)
            else -> currentIndex
        }
        return currentIndex
    }

    fun closeAll(privateOnly: Boolean = false) {
        if (privateOnly) {
            tabs.removeAll { it.private }
            currentIndex = if (tabs.isEmpty()) -1 else 0
        } else {
            tabs.clear(); currentIndex = -1
        }
    }

    fun update(id: Long, block: (TabMeta) -> Unit) {
        indexOf(id).let { if (it >= 0) block(tabs[it]) }
    }

    /** 活体 WebView 上限：按内存档位自适应（4GB→2，6GB→3，8GB+→4），可被设置覆盖。 */
    fun liveTabBudget(): Int {
        if (settings.tabLimit > 0) return settings.tabLimit
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val cls = am.memoryClass / 1024 // MB
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val gib = info.totalMem / (1024.0 * 1024.0 * 1024.0)
        return when {
            gib < 4.5 -> 2
            gib < 6.5 -> 3
            gib < 9.0 -> 4
            else -> if (cls > 400) 5 else 4
        }
    }

    /** 逐出评分：越小越先淘汰。当前标签页永不淘汰。 */
    fun evictionScore(tab: TabMeta, now: Long): Long {
        if (tab.id == current?.id) return Long.MAX_VALUE
        if (tab.hasMedia) return Long.MAX_VALUE - 1
        val age = now - tab.lastAccess
        return -age
    }

    fun isPrivateSession(): Boolean = current?.private == true

    fun persist() {
        try {
            val arr = JSONArray()
            tabs.filter { !it.private }.forEach { arr.put(it.toJson()) }
            context.openFileOutput(FILE, Context.MODE_PRIVATE).use { out ->
                out.write(arr.toString().toByteArray())
            }
        } catch (t: Throwable) {
            // 持久化失败不影响使用
        }
    }

    fun restore() {
        try {
            val text = context.openFileInput(FILE).bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            tabs.clear()
            for (i in 0 until arr.length()) tabs.add(TabMeta.fromJson(arr.getJSONObject(i)))
            currentIndex = if (tabs.isEmpty()) -1 else 0
        } catch (t: Throwable) {
            // 首次启动无文件
        }
    }

    companion object {
        private const val FILE = "velox.session.json"
    }
}
