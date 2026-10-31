package com.velox.browser.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView

/** 通用列表抽屉：历史、下载、统计共用。 */
class ListSheet(context: Context, private val title: String) : Sheet(context) {

    var onPick: ((String) -> Unit)? = null

    private val list = W.column(context, Theme.dp(context, 16f))

    init {
        val scroll = ScrollView(context)
        scroll.addView(list)
        buildContent().addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(context, 520f)
        ))
    }

    fun setItems(items: List<Triple<String, String, String>>, emptyText: String = "暂无内容") {
        list.removeAllViews()
        list.addView(W.text(context, title, 20f, bold = true).apply {
            setPadding(0, Theme.dp(context, 6f), 0, Theme.dp(context, 12f))
        })
        if (items.isEmpty()) {
            list.addView(W.text(context, emptyText, 13.5f, Theme.TEXT_TERTIARY).apply {
                setPadding(0, Theme.dp(context, 24f), 0, Theme.dp(context, 24f))
            })
            return
        }
        items.forEach { (primary, secondary, payload) ->
            val row = W.column(context).apply {
                setPadding(Theme.dp(context, 6f), Theme.dp(context, 11f), Theme.dp(context, 6f), Theme.dp(context, 11f))
                foreground = W.rippleBg(context, 12f)
                isClickable = true
                addView(W.text(context, primary, 14.5f).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (secondary.isNotEmpty()) addView(W.text(context, secondary, 11.5f, Theme.TEXT_TERTIARY).apply {
                    setPadding(0, Theme.dp(context, 3f), 0, 0)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                setOnClickListener { onPick?.invoke(payload); dismiss() }
            }
            list.addView(row)
        }
    }

    /** 纯文本视图（性能统计等）。 */
    fun setText(content: String) {
        list.removeAllViews()
        list.addView(W.text(context, title, 20f, bold = true).apply {
            setPadding(0, Theme.dp(context, 6f), 0, Theme.dp(context, 12f))
        })
        list.addView(W.text(context, content, 12.5f, 0xCCFFFFFF.toInt(), mono = true).apply {
            setLineSpacing(Theme.dpf(context, 4f), 1f)
        })
    }
}
