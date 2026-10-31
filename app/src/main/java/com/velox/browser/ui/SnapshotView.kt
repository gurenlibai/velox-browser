package com.velox.browser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

/**
 * 冷启动首帧视图：**完全不依赖 Chromium**。
 *
 * 引擎还在后台初始化时，这里已经把上一会话的画面贴到屏幕上，
 * 用户感知到的启动时间 ≈ 一次图片解码（典型 <16ms），
 * 而不是 WebView 首构的 182–400ms（RESEARCH §1.2）。
 */
class SnapshotView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = Rect()
    private val src = Rect()
    var onDrawn: (() -> Unit)? = null

    init {
        setBackgroundColor(Color.WHITE)
        visibility = GONE
    }

    fun setSnapshot(bmp: Bitmap?) {
        bitmap = bmp
        if (bmp == null) {
            visibility = GONE
        } else {
            visibility = VISIBLE
            alpha = 1f
            invalidate()
        }
    }

    fun fadeOut(duration: Long = 180) {
        if (visibility != VISIBLE) return
        animate().alpha(0f).setDuration(duration).withEndAction {
            visibility = GONE
            bitmap = null
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        // 顶部对齐（用户上次就在那个位置看），等比缩放填满宽度
        val scale = width.toFloat() / bmp.width
        val drawH = (bmp.height * scale).toInt()
        src.set(0, 0, bmp.width, bmp.height)
        dst.set(0, 0, width, if (drawH > height) height else drawH)
        canvas.drawBitmap(bmp, src, dst, paint)
        onDrawn?.invoke()
        onDrawn = null
    }
}
