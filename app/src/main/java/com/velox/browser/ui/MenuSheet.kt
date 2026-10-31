package com.velox.browser.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 主菜单：全部动作都在拇指区（底部），按使用频率排序而不是按功能分类。
 * 依据 RESEARCH §3：底部区域点按命中率 ~96%，顶部 ~61%。
 */
class MenuSheet(context: Context) : Sheet(context) {

    interface Actions {
        fun newTab()
        fun newPrivateTab()
        fun openTabs()
        fun history()
        fun downloads()
        fun toggleDesktop()
        fun toggleReader()
        fun sharePage()
        fun findInPage()
        fun toggleAdBlock()
        fun settings()
        fun about()
    }

    var actions: Actions? = null
    var adBlockOn: Boolean = true
    var desktopMode: Boolean = false

    private val list: LinearLayout
    private lateinit var adBlockRow: LinearLayout
    private lateinit var desktopRow: LinearLayout

    init {
        val scroll = ScrollView(context).apply { isFillViewport = true }
        list = W.column(context, Theme.dp(context, 12f))
        scroll.addView(list)
        buildContent().addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        rebuild()
    }

    fun refresh() {
        (adBlockRow.getChildAt(1) as TextView).text = if (adBlockOn) "已开启" else "已关闭"
        (desktopRow.getChildAt(1) as TextView).text = if (desktopMode) "已开启" else "已关闭"
    }

    private fun rebuild() {
        list.removeAllViews()
        list.addView(section("新建"))
        val quick = W.row(context)
        quick.addView(bigTile(IconView.Glyph.PLUS, "新标签页") { actions?.newTab(); dismiss() }, weight())
        quick.addView(space())
        quick.addView(bigTile(IconView.Glyph.SHIELD, "无痕标签") { actions?.newPrivateTab(); dismiss() }, weight())
        quick.addView(space())
        quick.addView(bigTile(IconView.Glyph.TABS, "标签页") { actions?.openTabs(); dismiss() }, weight())
        list.addView(quick)

        list.addView(section("当前页面"))
        list.addView(item(IconView.Glyph.SHARE, "分享页面") { actions?.sharePage(); dismiss() })
        list.addView(item(IconView.Glyph.FIND, "页内查找") { actions?.findInPage(); dismiss() })
        desktopRow = toggleItem(IconView.Glyph.GLOBE, "桌面版网站", desktopMode) {
            desktopMode = !desktopMode; actions?.toggleDesktop(); refresh()
        }
        list.addView(desktopRow)
        adBlockRow = toggleItem(IconView.Glyph.SHIELD, "广告与追踪拦截", adBlockOn) {
            adBlockOn = !adBlockOn; actions?.toggleAdBlock(); refresh()
        }
        list.addView(adBlockRow)

        list.addView(section("资料"))
        list.addView(item(IconView.Glyph.HISTORY, "历史记录") { actions?.history(); dismiss() })
        list.addView(item(IconView.Glyph.DOWNLOAD, "下载") { actions?.downloads(); dismiss() })

        list.addView(section(""))
        list.addView(item(IconView.Glyph.SETTINGS, "设置") { actions?.settings(); dismiss() })
        list.addView(item(IconView.Glyph.STAR, "关于 Velox") { actions?.about(); dismiss() })
    }

    private fun weight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun space() = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(Theme.dp(context, 8f), 1)
    }

    private fun section(title: String): View = W.text(context, title, 11.5f, Theme.TEXT_TERTIARY, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(Theme.dp(context, 6f), Theme.dp(context, 12f), Theme.dp(context, 6f), Theme.dp(context, 6f))
    }

    private fun bigTile(glyph: IconView.Glyph, label: String, onClick: () -> Unit): View =
        W.column(context, Theme.dp(context, 12f)).apply {
            background = W.tile(context, 20f, 0x12FFFFFF)
            isClickable = true
            isFocusable = true
            foreground = W.rippleBg(context, 20f)
            gravity = Gravity.CENTER
            addView(IconView(context).apply {
                this.glyph = glyph; tint = Theme.ACCENT
                layoutParams = LinearLayout.LayoutParams(Theme.dp(context, 26f), Theme.dp(context, 26f))
            })
            addView(W.text(context, label, 12.5f, Theme.TEXT_ON_DARK, bold = true).apply {
                gravity = Gravity.CENTER; setPadding(0, Theme.dp(context, 8f), 0, 0)
            })
            setOnClickListener { onClick() }
        }

    private fun item(glyph: IconView.Glyph, label: String, onClick: () -> Unit): View =
        W.row(context).apply {
            background = W.rippleBg(context, 14f)
            foreground = W.rippleBg(context, 14f)
            isClickable = true
            setPadding(Theme.dp(context, 10f), Theme.dp(context, 12f), Theme.dp(context, 10f), Theme.dp(context, 12f))
            addView(IconView(context).apply {
                this.glyph = glyph; tint = 0xCCFFFFFF.toInt()
                layoutParams = LinearLayout.LayoutParams(Theme.dp(context, 22f), Theme.dp(context, 22f))
            })
            addView(W.text(context, label, 15f).apply {
                setPadding(Theme.dp(context, 14f), 0, 0, 0)
            })
            setOnClickListener { onClick() }
        }

    private fun toggleItem(glyph: IconView.Glyph, label: String, on: Boolean, onClick: () -> Unit): LinearLayout =
        W.row(context).apply {
            background = W.rippleBg(context, 14f)
            foreground = W.rippleBg(context, 14f)
            isClickable = true
            setPadding(Theme.dp(context, 10f), Theme.dp(context, 12f), Theme.dp(context, 10f), Theme.dp(context, 12f))
            addView(IconView(context).apply {
                this.glyph = glyph; tint = 0xCCFFFFFF.toInt()
                layoutParams = LinearLayout.LayoutParams(Theme.dp(context, 22f), Theme.dp(context, 22f))
            })
            addView(W.text(context, if (on) "已开启" else "已关闭", 13f, Theme.ACCENT).apply {
                setPadding(Theme.dp(context, 14f), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(W.text(context, label, 15f))
            setOnClickListener { onClick() }
        }
}
