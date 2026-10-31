package com.velox.browser.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.velox.browser.prefs.Settings
import com.velox.browser.util.Urls
import kotlin.math.abs

/**
 * 三态工具栏：PINNED / COLLAPSED / HIDDEN。
 *
 * 与所有主流浏览器的根本差异（docs/ARCHITECTURE.md §2.5）：
 * 本控件是**叠层**，其高度变化只改变自身 `translationY`，**从不改变 WebView 的
 * layout 尺寸**。因此「滚动时地址栏收起」这一动作在本浏览器里**不产生任何
 * viewport 变化**，页面不会重排、100vh 元素不会跳动、`resize` 不会触发。
 * 这是研究中记录的「浏览器自己制造的 jank」的结构性消除。
 */
class ChromeBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    enum class Mode { PINNED, COLLAPSED, HIDDEN }

    interface Listener {
        fun onBack()
        fun onForward()
        fun onReload()
        fun onOpenTabs()
        fun onOpenMenu()
        fun onOmniboxTap()
        fun onModeChanged(mode: Mode)
    }

    var listener: Listener? = null
    var mode: Mode = Mode.PINNED
        private set

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    private val gripHeight = dp(22f)
    private val rowHeight = dp(60f)
    val pinnedHeight = gripHeight + rowHeight

    private val grip: GripView = GripView(context)
    private val row: LinearLayout = LinearLayout(context)
    private val back: IconView = icon(IconView.Glyph.BACK)
    private val forward: IconView = icon(IconView.Glyph.FORWARD)
    private val tabs: IconView = icon(IconView.Glyph.TABS)
    private val menu: IconView = icon(IconView.Glyph.MENU)
    private val pill: PillView = PillView(context)
    private val progressBar: ProgressLine = ProgressLine(context)

    private var animator: ValueAnimator? = null
    private var dragStartY = 0f
    private var dragStartTranslation = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L

    init {
        setWillNotDraw(false)
        background = null
        clipChildren = false
        clipToPadding = false

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            background = Theme.glass(context)
            clipToOutline = true
        }
        column.addView(grip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gripHeight))

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val pad = dp(6f)
        row.setPadding(pad, 0, pad, 0)
        row.addView(back, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        row.addView(forward, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        row.addView(pill, LinearLayout.LayoutParams(0, dp(46f), 1f).apply {
            marginStart = dp(4f); marginEnd = dp(4f)
        })
        row.addView(tabs, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        row.addView(menu, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight))

        addView(column)
        addView(progressBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(2f), Gravity.TOP))
        progressBar.alpha = 0f

        wire()
        applyMode(Mode.PINNED, animate = false)
    }

    private fun icon(g: IconView.Glyph) = IconView(context).apply {
        glyph = g
        tint = Theme.TEXT_ON_DARK
        isClickable = true
        isFocusable = true
        background = ripple(dp(22f))
    }

    private fun ripple(radius: Int) = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x33FFFFFF),
        null,
        GradientDrawable().apply { cornerRadius = radius.toFloat(); setColor(Color.WHITE) }
    )

    private fun wire() {
        back.setOnClickListener { listener?.onBack(); haptic() }
        forward.setOnClickListener { listener?.onForward(); haptic() }
        tabs.setOnClickListener { listener?.onOpenTabs(); haptic() }
        menu.setOnClickListener { listener?.onOpenMenu(); haptic() }
        pill.setOnClickListener { listener?.onOmniboxTap(); haptic() }

        val drag = object : OnTouchListener {
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownY = e.rawY
                        dragStartY = e.rawY
                        dragStartTranslation = translationY
                        touchDownTime = System.currentTimeMillis()
                        animator?.cancel()
                        (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = e.rawY - dragStartY
                        translationY = (dragStartTranslation + dy).coerceIn(0f, pinnedHeight.toFloat())
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dy = e.rawY - dragStartY
                        val travel = abs(dy)
                        val elapsed = System.currentTimeMillis() - touchDownTime
                        val fling = travel / elapsed.coerceAtLeast(1)
                        when {
                            // 上滑：完全展开
                            dy < -dp(10f) -> setMode(Mode.PINNED)
                            // 下滑：收起；快速下滑直接隐藏
                            dy > dp(10f) && (fling > 0.6f || dy > pinnedHeight * 0.6f) -> setMode(Mode.HIDDEN)
                            dy > dp(10f) -> setMode(Mode.COLLAPSED)
                            travel < dp(6f) && elapsed < 220 -> {
                                // 轻点 grip：在「展开/收起」间切换
                                setMode(if (mode == Mode.PINNED) Mode.COLLAPSED else Mode.PINNED)
                            }
                            else -> applyMode(mode, animate = true)
                        }
                        return true
                    }
                }
                return false
            }
        }
        grip.setOnTouchListener(drag)
    }

    fun setMode(m: Mode) {
        mode = m
        applyMode(m, animate = true)
        listener?.onModeChanged(m)
    }

    private fun applyMode(m: Mode, animate: Boolean) {
        val target = when (m) {
            Mode.PINNED -> 0f
            Mode.COLLAPSED -> (pinnedHeight - dp(46f)).toFloat()
            Mode.HIDDEN -> pinnedHeight.toFloat()
        }
        animator?.cancel()
        if (!animate) { translationY = target; onBarMoved(target); return }
        animator = ValueAnimator.ofFloat(translationY, target).apply {
            duration = 170
            interpolator = android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
            addUpdateListener { translationY = it.animatedValue as Float; onBarMoved(translationY) }
            start()
        }
    }

    private fun onBarMoved(t: Float) {
        // 工具栏透明度随位置微调，隐藏一半以上时开始淡出，避免「半截栏杆」
        val frac = 1f - (t / pinnedHeight).coerceIn(0f, 1f)
        grip.alpha = (frac * 1.4f).coerceIn(0f, 1f)
        row.alpha = ((frac - 0.22f) / 0.78f).coerceIn(0f, 1f)
    }

    private fun haptic() {
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    // ---- 状态更新 ----

    fun setUrl(url: String, secure: Boolean, host: String) {
        pill.setAddress(url, secure, host)
    }

    fun setNavState(canBack: Boolean, canForward: Boolean) {
        back.alpha = if (canBack) 1f else 0.32f
        forward.alpha = if (canForward) 1f else 0.32f
        back.isEnabled = canBack
        forward.isEnabled = canForward
    }

    fun setLoading(loading: Boolean) {
        pill.setLoading(loading)
    }

    fun setTabCount(n: Int) {
        tabs.badge = if (n > 1) n.toString() else null
    }

    fun setProgress(p: Int) {
        progressBar.setProgress(p)
        val target = if (p in 1..99) 1f else 0f
        if (progressBar.alpha != target) {
            progressBar.animate().cancel()
            progressBar.animate().alpha(target).setDuration(if (target == 0f) 240 else 60).start()
        }
    }

    fun setBlockedCount(n: Long) {
        pill.setBlocked(n)
    }

    // ---- 内部视图 ----

    /** 顶部拖拽手柄：视觉锚点，同时也是「收起/展开」的触控命中区。 */
    private class GripView(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
        private val r = android.graphics.RectF()
        override fun onDraw(canvas: Canvas) {
            val w = width * 0.14f
            val h = height * 0.11f
            r.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
            canvas.drawRoundRect(r, h, h, p)
        }
    }

    /** 地址胶囊：左侧安全标识 + 主机名（重）+ 路径（淡）+ 右侧刷新/停止。 */
    private class PillView(context: Context) : FrameLayout(context) {
        private val hostText = TextView(context).apply {
            typeface = Theme.mono
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Theme.TEXT_ON_DARK)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER_VERTICAL
        }
        private val pathText = TextView(context).apply {
            typeface = Theme.regular
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0x99FFFFFF.toInt())
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        private val glyph = IconView(context).apply { glyph = IconView.Glyph.LOCK; tint = Theme.ACCENT }
        private val action = IconView(context).apply { glyph = IconView.Glyph.RELOAD; tint = 0xCCFFFFFF.toInt() }
        private val dot = View(context)
        private var loading = false

        init {
            background = GradientDrawable().apply {
                cornerRadius = Theme.dpf(context, 23f)
                setColor(0x26FFFFFF)
                setStroke(Theme.dp(context, 1f), 0x1FFFFFFF)
            }
            addView(glyph, LayoutParams(Theme.dp(context, 18f), Theme.dp(context, 18f), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                marginStart = Theme.dp(context, 12f)
            })
            addView(hostText, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                marginStart = Theme.dp(context, 38f)
            })
            addView(pathText, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                marginStart = Theme.dp(context, 38f)
            })
            addView(action, LayoutParams(Theme.dp(context, 20f), Theme.dp(context, 20f), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = Theme.dp(context, 12f)
            })
            dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Theme.ACCENT) }
            addView(dot, LayoutParams(Theme.dp(context, 6f), Theme.dp(context, 6f), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = Theme.dp(context, 40f)
            })
            dot.visibility = GONE
        }

        fun setAddress(url: String, secure: Boolean, host: String) {
            glyph.glyph = if (secure) IconView.Glyph.LOCK else IconView.Glyph.GLOBE
            glyph.tint = if (secure) Theme.ACCENT else 0x88FFFFFF.toInt()
            val display = UrlParts.split(url, host)
            hostText.text = display.first
            if (display.second.isEmpty()) {
                pathText.visibility = GONE
                hostText.layoutParams = (hostText.layoutParams as LayoutParams).apply { marginEnd = Theme.dp(context, 44f) }
            } else {
                pathText.visibility = VISIBLE
                pathText.text = display.second
                // 用固定宽度把「主机名 → 路径」拼在一起：host 用 wrap，path 占剩余
                val hostWidth = hostText.paint.measureText(display.first).toInt() + Theme.dp(context, 8f)
                hostText.layoutParams = (hostText.layoutParams as LayoutParams).apply {
                    width = hostWidth.coerceAtMost((width * 0.6f).toInt()); marginEnd = 0
                }
                pathText.layoutParams = (pathText.layoutParams as LayoutParams).apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT; marginStart = Theme.dp(context, 38f) + hostWidth
                    marginEnd = Theme.dp(context, 44f)
                }
            }
            requestLayout()
        }

        fun setLoading(loading: Boolean) {
            this.loading = loading
            action.glyph = if (loading) IconView.Glyph.CLOSE else IconView.Glyph.RELOAD
        }

        fun setBlocked(n: Long) {
            dot.visibility = if (n > 0) VISIBLE else GONE
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private class ProgressLine(context: Context) : View(context) {
        private var p = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ACCENT }
        fun setProgress(v: Int) { p = (v / 100f).coerceIn(0f, 1f); invalidate() }
        override fun onDraw(canvas: Canvas) {
            if (p <= 0f) return
            canvas.drawRect(0f, 0f, width * p, height.toFloat(), paint)
        }
    }

    object UrlParts {
        fun split(url: String, host: String): Pair<String, String> {
            if (url.isEmpty()) return "输入网址或搜索" to ""
            val clean = url.removePrefix("https://").removePrefix("http://")
            val idx = clean.indexOfFirst { it == '/' || it == '?' || it == '#' }
            return if (idx <= 0) clean to ""
            else clean.substring(0, idx) to clean.substring(idx)
        }
    }
}
