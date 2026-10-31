package com.velox.browser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import com.velox.browser.ui.TabSwitcher.Handler
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.velox.browser.browser.TabMeta

/**
 * 标签页总览。
 *
 * 与 Chrome 的差异（RESEARCH §3「找标签页成本 ~2.8s」）：
 *  - 卡片自带**上次可见快照**，扫一眼就能认出是哪一页，不靠猜标题；
 *  - 每个标签页的关闭按钮固定在同一位置（右下），支持快速连删；
 *  - 底部固定「新标签页」与「关闭全部」，不滚动。
 */
class TabSwitcher(context: Context) : Sheet(context) {

    interface Handler {
        fun onSelect(id: Long)
        fun onClose(id: Long)
        fun onNewTab()
        fun onCloseAll()
    }

    var listener: TabSwitcher.Handler? = null
    private val list = W.column(context, Theme.dp(context, 14f))
    private var snapshots: ((Long) -> Bitmap?)? = null

    private val footer = W.row(context)

    init {
        val root = W.column(context, 0)
        val header = W.row(context).apply {
            setPadding(Theme.dp(context, 20f), Theme.dp(context, 4f), Theme.dp(context, 12f), Theme.dp(context, 8f))
        }
        header.addView(W.text(context, "标签页", 20f, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        val scroll = ScrollView(context)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { height = Theme.dp(context, 1f) })

        footer.setPadding(Theme.dp(context, 14f), Theme.dp(context, 8f), Theme.dp(context, 14f), Theme.dp(context, 10f))
        root.addView(footer)

        buildContent().addView(root, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(context, 560f)
        ))
    }

    fun bind(tabs: List<TabMeta>, currentId: Long?, snapshotProvider: (Long) -> Bitmap?) {
        snapshots = snapshotProvider
        list.removeAllViews()
        tabs.reversed().forEach { tab ->
            list.addView(card(tab, tab.id == currentId))
        }
        footer.removeAllViews()
        footer.addView(W.text(context, "共 ${tabs.size} 个", 13f, Theme.TEXT_TERTIARY),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(button("关闭全部") { listener?.onCloseAll(); dismiss() })
        footer.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(Theme.dp(context, 8f), 1) })
        footer.addView(button("＋ 新标签页", accent = true) { listener?.onNewTab(); dismiss() })
    }

    private fun card(tab: TabMeta, selected: Boolean): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = Theme.dpf(context, 18f)
                setColor(if (selected) 0x1F14D6A4 else 0x12FFFFFF)
                setStroke(Theme.dp(context, 1f), if (selected) Theme.ACCENT else 0x14FFFFFF)
            }
            isClickable = true
            foreground = W.rippleBg(context, 18f)
            setPadding(Theme.dp(context, 10f), Theme.dp(context, 10f), Theme.dp(context, 8f), Theme.dp(context, 10f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Theme.dp(context, 10f) }
            setOnClickListener { val hdl = listener; if (hdl != null) { hdl.onSelect(tab.id) }; dismiss() }
        }
        val thumb = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = Theme.dpf(context, 12f); setColor(0x22FFFFFF)
            }
            clipToOutline = true
        }
        val bmp = snapshots?.invoke(tab.id)
        if (bmp != null) thumb.setImageBitmap(bmp)
        card.addView(thumb, LinearLayout.LayoutParams(Theme.dp(context, 68f), Theme.dp(context, 92f)))

        val meta = W.column(context).apply {
            setPadding(Theme.dp(context, 14f), 0, Theme.dp(context, 6f), 0)
            addView(W.text(context, tab.title.ifBlank { tab.url.ifBlank { "新标签页" } }, 15f,
                if (selected) Theme.ACCENT else Theme.TEXT_ON_DARK, bold = true).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(W.text(context, shorten(tab.url), 12f, Theme.TEXT_TERTIARY).apply {
                setPadding(0, Theme.dp(context, 4f), 0, 0)
                maxLines = 1
            })
            if (tab.private) addView(W.text(context, "无痕", 10.5f, Theme.ACCENT, bold = true).apply {
                setPadding(0, Theme.dp(context, 6f), 0, 0)
            })
        }
        card.addView(meta, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val close = IconView(context).apply {
            glyph = IconView.Glyph.CLOSE
            tint = 0x99FFFFFF.toInt()
            background = Theme.pill(0x14FFFFFF, 18f, context)
            setOnClickListener { val hdl = listener; if (hdl != null) { hdl.onClose(tab.id) }; dismiss() }
        }
        card.addView(close, LinearLayout.LayoutParams(Theme.dp(context, 34f), Theme.dp(context, 34f)))
        return card
    }

    private fun button(label: String, accent: Boolean = false, onClick: () -> Unit): View =
        W.text(context, label, 13.5f, if (accent) Theme.INK else Theme.TEXT_ON_DARK, bold = true).apply {
            setPadding(Theme.dp(context, 16f), Theme.dp(context, 10f), Theme.dp(context, 16f), Theme.dp(context, 10f))
            background = Theme.pill(if (accent) Theme.ACCENT else 0x1AFFFFFF, 20f, context)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun shorten(url: String): String {
        if (url.isBlank()) return "无内容"
        return url.removePrefix("https://").removePrefix("http://").take(64)
    }
}
