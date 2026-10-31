package com.velox.browser.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * 轻量底部抽屉：自绘，不依赖 Material 组件库。
 * 动画只动 translationY（合成层内），开启硬件层以保证 120Hz 下不掉帧。
 */
abstract class Sheet(context: Context) : FrameLayout(context) {

    private val scrim = View(context)
    protected val panel = LinearLayout(context)
    private val handle = View(context)

    var onDismissed: (() -> Unit)? = null

    private var downY = 0f
    private var dragStart = 0f
    private var downTime = 0L
    private var animator: ValueAnimator? = null

    init {
        visibility = GONE
        setWillNotDraw(false)
        scrim.setBackgroundColor(0x99000000.toInt())
        scrim.alpha = 0f
        scrim.setOnClickListener { dismiss() }
        addView(scrim, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        panel.orientation = LinearLayout.VERTICAL
        panel.background = Theme.sheet(context)
        panel.clipToOutline = true
        panel.elevation = Theme.dpf(context, 16f)
        panel.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        handle.background = GradientDrawable().apply {
            cornerRadius = Theme.dpf(context, 3f)
            setColor(0x55FFFFFF)
        }
        panel.addView(handle, LinearLayout.LayoutParams(Theme.dp(context, 38f), Theme.dp(context, 4f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = Theme.dp(context, 10f)
            bottomMargin = Theme.dp(context, 6f)
        })

        val params = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        params.bottomMargin = Theme.dp(context, 0f)
        addView(panel, params)

        panel.setOnTouchListener(object : OnTouchListener {
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = e.rawY; dragStart = panel.translationY
                        downTime = System.currentTimeMillis(); animator?.cancel()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = (e.rawY - downY).coerceAtLeast(0f)
                        panel.translationY = dragStart + dy
                        scrim.alpha = (1f - (panel.translationY / panel.height.coerceAtLeast(1))).coerceIn(0f, 1f) * 0.6f
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dy = e.rawY - downY
                        val velocity = dy / (System.currentTimeMillis() - downTime).coerceAtLeast(1)
                        if (dy > panel.height * 0.28f || velocity > 0.9f) dismiss() else snapBack()
                    }
                }
                return false
            }
        })
    }

    protected fun buildContent() = panel

    override fun onAttachedToWindow() { super.onAttachedToWindow() }

    open fun show() {
        if (visibility == VISIBLE) return
        visibility = VISIBLE
        panel.translationY = Theme.dpf(context, 420f)
        panel.alpha = 1f
        scrim.alpha = 0f
        panel.post {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(panel.translationY, 0f).apply {
                duration = 210
                interpolator = android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)
                addUpdateListener { panel.translationY = it.animatedValue as Float }
                start()
            }
            scrim.animate().alpha(0.6f).setDuration(180).start()
        }
        onShown()
    }

    open fun dismiss() {
        if (visibility != VISIBLE) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(panel.translationY, panel.height.toFloat()).apply {
            duration = 160
            interpolator = android.view.animation.AccelerateInterpolator()
            addUpdateListener { panel.translationY = it.animatedValue as Float }
            doOnEnd { visibility = GONE; panel.translationY = 0f; onDismissed?.invoke() }
            start()
        }
        scrim.animate().alpha(0f).setDuration(140).start()
    }

    private fun snapBack() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(panel.translationY, 0f).apply {
            duration = 140
            addUpdateListener { panel.translationY = it.animatedValue as Float }
            start()
        }
        scrim.animate().alpha(0.6f).setDuration(140).start()
    }

    private fun ValueAnimator.doOnEnd(block: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = block()
        })
    }

    protected open fun onShown() {}
}
