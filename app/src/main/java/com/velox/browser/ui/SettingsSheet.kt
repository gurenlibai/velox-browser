package com.velox.browser.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import com.velox.browser.prefs.Settings

/**
 * 设置：只放**有真实效果**的开关，并就地显示当前内核能力，避免「假开关」。
 */
class SettingsSheet(context: Context) : Sheet(context) {

    interface Handler {
        fun onChanged()
        fun onOpenStats()
        fun onClearData()
    }

    var listener: Handler? = null
    lateinit var settings: Settings
    var capabilities: String = ""
    var tabBudget: Int = 3

    private val list = W.column(context, Theme.dp(context, 16f))

    init {
        val scroll = ScrollView(context)
        scroll.addView(list)
        buildContent().addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(context, 600f)
        ))
    }

    fun bind() {
        list.removeAllViews()
        list.addView(W.text(context, "设置", 20f, bold = true).apply {
            setPadding(0, Theme.dp(context, 6f), 0, Theme.dp(context, 10f))
        })

        list.addView(section("流畅度"))
        list.addView(info("活动标签页上限", "$tabBudget 个 · WebView 单 renderer，多留会拖慢每帧"))
        list.addView(choice("推测加载", listOf("关闭", "预连接", "预取", "预渲染"),
            settings.speculationLevel) { i -> settings.speculationLevel = i; listener?.onChanged() })
        list.addView(info("技术依据", "手指按下即预测目标：预取命中可省 130–300ms"))

        list.addView(section("拦截与隐私"))
        list.addView(toggle("广告与追踪拦截", settings.adBlockEnabled) {
            settings.adBlockEnabled = it; listener?.onChanged()
        })
        list.addView(toggle("阻止第三方 Cookie", settings.blockThirdPartyCookies) {
            settings.blockThirdPartyCookies = it; listener?.onChanged()
        })
        list.addView(toggle("启用 JavaScript", settings.javascriptEnabled) {
            settings.javascriptEnabled = it; listener?.onChanged()
        })
        list.addView(info("遥测", "0 项 · Velox 不发送任何使用数据"))

        list.addView(section("界面"))
        list.addView(choice("工具栏", listOf("折叠细条", "常驻", "自动隐藏"), settings.toolbarMode) { i ->
            settings.toolbarMode = i; listener?.onChanged()
        })
        list.addView(choice("搜索引擎", listOf("DuckDuckGo", "Google", "Bing"), settings.searchEngine) { i ->
            settings.searchEngine = i; listener?.onChanged()
        })

        list.addView(section("内核能力（实时探测）"))
        list.addView(info("当前设备", capabilities))

        list.addView(section("数据"))
        list.addView(action("查看本机性能采样") { listener?.onOpenStats() })
        list.addView(action("清除本地数据") { listener?.onClearData() })

        list.addView(W.text(context, "Velox 1.0.0 · 无账号 · 无云同步 · 无遥测",
            11.5f, Theme.TEXT_TERTIARY).apply {
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(context, 18f), 0, Theme.dp(context, 8f))
        })
    }

    private fun section(t: String) = W.text(context, t, 11.5f, Theme.TEXT_TERTIARY, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(0, Theme.dp(context, 16f), 0, Theme.dp(context, 8f))
    }

    private fun info(title: String, value: String): LinearLayout = W.column(context).apply {
        setPadding(0, Theme.dp(context, 6f), 0, Theme.dp(context, 10f))
        addView(W.text(context, title, 14f))
        addView(W.text(context, value, 12f, Theme.TEXT_TERTIARY).apply {
            setPadding(0, Theme.dp(context, 3f), 0, 0)
        })
    }

    private fun toggle(title: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        var on = initial
        val row = W.row(context)
        row.background = W.rippleBg(context, 14f)
        row.foreground = W.rippleBg(context, 14f)
        row.isClickable = true
        row.setPadding(0, Theme.dp(context, 13f), 0, Theme.dp(context, 13f))
        row.addView(W.text(context, title, 14.5f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val state = W.text(context, if (on) "开" else "关", 13f, if (on) Theme.ACCENT else Theme.TEXT_TERTIARY, bold = true).apply {
            setPadding(Theme.dp(context, 12f), Theme.dp(context, 4f), Theme.dp(context, 12f), Theme.dp(context, 4f))
            background = Theme.pill(if (on) 0x1F14D6A4 else 0x14FFFFFF, 14f, context)
        }
        row.addView(state)
        row.setOnClickListener {
            on = !on
            state.text = if (on) "开" else "关"
            state.setTextColor(if (on) Theme.ACCENT else Theme.TEXT_TERTIARY)
            state.background = Theme.pill(if (on) 0x1F14D6A4 else 0x14FFFFFF, 14f, context)
            onChange(on)
        }
        return row
    }

    private fun choice(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout =
        W.column(context).apply {
            setPadding(0, Theme.dp(context, 10f), 0, Theme.dp(context, 6f))
            addView(W.text(context, title, 14.5f).apply { setPadding(0, 0, 0, Theme.dp(context, 8f)) })
            val row = W.row(context)
            var current = selected
            val buttons = mutableListOf<android.widget.TextView>()
            options.forEachIndexed { i, label ->
                val tv = W.text(context, label, 12.5f,
                    if (i == current) Theme.INK else 0xCCFFFFFF.toInt(), bold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(Theme.dp(context, 10f), Theme.dp(context, 9f), Theme.dp(context, 10f), Theme.dp(context, 9f))
                    background = Theme.pill(if (i == current) Theme.ACCENT else 0x14FFFFFF, 16f, context)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = Theme.dp(context, 6f)
                    }
                    setOnClickListener {
                        current = i
                        buttons.forEachIndexed { j, b ->
                            b.setTextColor(if (j == current) Theme.INK else 0xCCFFFFFF.toInt())
                            b.background = Theme.pill(if (j == current) Theme.ACCENT else 0x14FFFFFF, 16f, context)
                        }
                        onPick(i)
                    }
                }
                buttons.add(tv)
                row.addView(tv)
            }
            addView(row)
        }

    private fun action(title: String, onClick: () -> Unit): LinearLayout = W.row(context).apply {
        foreground = W.rippleBg(context, 14f)
        isClickable = true
        setPadding(0, Theme.dp(context, 14f), 0, Theme.dp(context, 14f))
        addView(W.text(context, title, 14.5f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(W.text(context, "›", 18f, Theme.TEXT_TERTIARY))
        setOnClickListener { onClick() }
    }
}
