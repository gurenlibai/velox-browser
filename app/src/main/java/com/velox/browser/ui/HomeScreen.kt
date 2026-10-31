package com.velox.browser.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * 新标签页 / 主页。
 *
 * 设计意图（RESEARCH §3）：
 *  - 搜索框放在屏幕下半部的拇指区，而不是顶部（顶部命中率 61% vs 底部 96%）；
 *  - 快捷入口是「最近真正访问的站点」而不是广告位式的推荐内容；
 *  - 首屏信息量刻意克制：一个输入框 + 最多 8 个入口，没有资讯流（不消耗流量、不分散注意）。
 */
class HomeScreen(context: Context) : ScrollView(context) {

    interface Actions {
        fun onSearch(query: String)
        fun onOpen(url: String)
        fun onQuickAccessLongPress(url: String)
    }

    var actions: Actions? = null

    private val root = W.column(context)
    private val input: EditText
    private val quickGrid: LinearLayout
    private val recentRow: LinearLayout

    init {
        isFillViewport = true
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF0B0E14L.toInt(), 0xFF0B0E14L.toInt()))
        addView(root)

        root.setPadding(Theme.dp(context, 20f), 0, Theme.dp(context, 20f), Theme.dp(context, 28f))

        // 让内容落在下半屏：顶部留白（但不再是空白，而是品牌）
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, Theme.dp(context, 96f), 1f)
        })
        root.addView(W.text(context, "Velox", 30f, Theme.TEXT_ON_DARK, bold = true).apply {
            letterSpacing = -0.02f
        })
        root.addView(W.text(context, "快，是唯一的设计语言", 13f, Theme.TEXT_TERTIARY).apply {
            setPadding(0, Theme.dp(context, 4f), 0, Theme.dp(context, 18f))
        })

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = Theme.dpf(context, 26f)
                setColor(0x1AFFFFFF)
                setStroke(Theme.dp(context, 1f), 0x22FFFFFF)
            }
            setPadding(Theme.dp(context, 16f), 0, Theme.dp(context, 10f), 0)
        }
        box.addView(IconView(context).apply {
            glyph = IconView.Glyph.SEARCH; tint = Theme.TEXT_TERTIARY
            layoutParams = LinearLayout.LayoutParams(Theme.dp(context, 20f), Theme.dp(context, 20f))
        })
        input = EditText(context).apply {
            hint = "搜索或输入网址"
            setHintTextColor(Theme.TEXT_TERTIARY)
            setTextColor(Theme.TEXT_ON_DARK)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(Theme.dp(context, 12f), 0, Theme.dp(context, 8f), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    submit(); true
                } else false
            }
        }
        box.addView(input, LinearLayout.LayoutParams(0, Theme.dp(context, 54f), 1f))
        root.addView(box)

        root.addView(W.text(context, "快速访问", 11.5f, Theme.TEXT_TERTIARY, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(0, Theme.dp(context, 26f), 0, Theme.dp(context, 10f))
        })
        quickGrid = W.column(context)
        root.addView(quickGrid)

        root.addView(W.text(context, "最近", 11.5f, Theme.TEXT_TERTIARY, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(0, Theme.dp(context, 22f), 0, Theme.dp(context, 10f))
        })
        recentRow = W.column(context)
        root.addView(recentRow)
    }

    fun focusSearch() {
        input.requestFocus()
        input.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun clearSearch() {
        input.setText("")
    }

    private fun submit() {
        val q = input.text?.toString()?.trim().orEmpty()
        if (q.isNotEmpty()) actions?.onSearch(q)
    }

    fun setQuickAccess(entries: List<Pair<String, String>>) {
        quickGrid.removeAllViews()
        var row = W.row(context)
        entries.take(8).forEachIndexed { i, (url, title) ->
            if (i > 0 && i % 4 == 0) { quickGrid.addView(row); row = W.row(context) }
            row.addView(quickTile(url, title), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        if (row.childCount > 0) quickGrid.addView(row)
    }

    fun setRecent(entries: List<Triple<String, String, Long>>) {
        recentRow.removeAllViews()
        entries.take(4).forEach { (url, title, _) ->
            recentRow.addView(recentItem(url, title))
        }
    }

    private fun quickTile(url: String, title: String): View =
        W.column(context, Theme.dp(context, 10f)).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = Theme.dpf(context, 18f)
                setColor(0x12FFFFFF)
                setStroke(Theme.dp(context, 1f), 0x14FFFFFF)
            }
            isClickable = true
            foreground = W.rippleBg(context, 18f)
            val letter = W.text(context, title.take(1).uppercase(), 19f, Theme.ACCENT, bold = true).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(Theme.dp(context, 40f), Theme.dp(context, 40f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(0x1F14D6A4)
                }
            }
            addView(letter)
            addView(W.text(context, title.take(10), 11.5f, 0xCCFFFFFF.toInt()).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, Theme.dp(context, 6f), 0, 0)
            })
            setOnClickListener { actions?.onOpen(url) }
            setOnLongClickListener { actions?.onQuickAccessLongPress(url); true }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(Theme.dp(context, 3f), 0, Theme.dp(context, 3f), Theme.dp(context, 6f))
            }
        }

    private fun recentItem(url: String, title: String): View =
        W.row(context).apply {
            setPadding(Theme.dp(context, 4f), Theme.dp(context, 9f), Theme.dp(context, 4f), Theme.dp(context, 9f))
            foreground = W.rippleBg(context, 12f)
            isClickable = true
            addView(W.text(context, title.ifBlank { url }, 14f, 0xCCFFFFFF.toInt()).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(W.text(context, "↗", 13f, Theme.TEXT_TERTIARY))
            setOnClickListener { actions?.onOpen(url) }
        }
}
