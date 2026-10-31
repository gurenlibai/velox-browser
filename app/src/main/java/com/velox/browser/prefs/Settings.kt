package com.velox.browser.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * 全部设置本地保存，无账号、无同步、无遥测。
 */
class Settings(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("velox.settings", Context.MODE_PRIVATE)

    /** 工具栏形态：0 折叠细条 / 1 常驻 / 2 自动隐藏 */
    var toolbarMode: Int
        get() = sp.getInt(KEY_TOOLBAR_MODE, MODE_PINNED)
        set(v) = sp.edit().putInt(KEY_TOOLBAR_MODE, v).apply()

    var adBlockEnabled: Boolean
        get() = sp.getBoolean(KEY_ADBLOCK, true)
        set(v) = sp.edit().putBoolean(KEY_ADBLOCK, v).apply()

    var blockThirdPartyCookies: Boolean
        get() = sp.getBoolean(KEY_3P_COOKIES, true)
        set(v) = sp.edit().putBoolean(KEY_3P_COOKIES, v).apply()

    var javascriptEnabled: Boolean
        get() = sp.getBoolean(KEY_JS, true)
        set(v) = sp.edit().putBoolean(KEY_JS, v).apply()

    /** 意图预取等级：0 关 / 1 仅预连接 / 2 预取 / 3 预渲染 */
    var speculationLevel: Int
        get() = sp.getInt(KEY_SPECULATION, SPECULATION_FULL)
        set(v) = sp.edit().putInt(KEY_SPECULATION, v).apply()

    var searchEngine: Int
        get() = sp.getInt(KEY_SEARCH, SEARCH_DUCKDUCKGO)
        set(v) = sp.edit().putInt(KEY_SEARCH, v).apply()

    var lastClosedUrl: String?
        get() = sp.getString(KEY_LAST_URL, null)
        set(v) = sp.edit().putString(KEY_LAST_URL, v).apply()

    var onboardingDone: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(v) = sp.edit().putBoolean(KEY_ONBOARDED, v).apply()

    var tabLimit: Int
        get() = sp.getInt(KEY_TAB_LIMIT, 0)
        set(v) = sp.edit().putInt(KEY_TAB_LIMIT, v).apply()

    companion object {
        private const val KEY_TOOLBAR_MODE = "toolbar.mode"
        private const val KEY_ADBLOCK = "adblock.enabled"
        private const val KEY_3P_COOKIES = "cookies.thirdparty"
        private const val KEY_JS = "js.enabled"
        private const val KEY_SPECULATION = "speculation.level"
        private const val KEY_SEARCH = "search.engine"
        private const val KEY_LAST_URL = "session.lastUrl"
        private const val KEY_ONBOARDED = "onboarding.done"
        private const val KEY_TAB_LIMIT = "tabs.limit"

        const val MODE_COLLAPSED = 0
        const val MODE_PINNED = 1
        const val MODE_HIDDEN = 2

        const val SPECULATION_OFF = 0
        const val SPECULATION_PRECONNECT = 1
        const val SPECULATION_PREFETCH = 2
        const val SPECULATION_FULL = 3

        const val SEARCH_DUCKDUCKGO = 0
        const val SEARCH_GOOGLE = 1
        const val SEARCH_BING = 2

        fun searchUrl(engine: Int): String = when (engine) {
            SEARCH_GOOGLE -> "https://www.google.com/search?q="
            SEARCH_BING -> "https://www.bing.com/search?q="
            else -> "https://duckduckgo.com/?q="
        }

        fun searchName(engine: Int): String = when (engine) {
            SEARCH_GOOGLE -> "Google"
            SEARCH_BING -> "Bing"
            else -> "DuckDuckGo"
        }
    }
}
