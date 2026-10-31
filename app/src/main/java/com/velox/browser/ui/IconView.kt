package com.velox.browser.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 纯代码绘制的图标（无 PNG/SVG 资源）：
 * 好处是任意密度下都锐利，APK 体积几乎不增加，且换色只需一个 setter。
 */
class IconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class Glyph {
        BACK, FORWARD, RELOAD, CLOSE, PLUS, TABS, MENU, HOME, SHIELD, SHARE,
        SETTINGS, STAR, SEARCH, CHEVRON_DOWN, CHEVRON_UP, LOCK, GLOBE, HISTORY, DOWNLOAD, FIND
    }

    var glyph: Glyph = Glyph.MENU
        set(v) { field = v; invalidate() }

    var tint: Int = Theme.TEXT_ON_DARK
        set(v) { field = v; paint.color = v; invalidate() }

    var strokeWidthFactor: Float = 0.085f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = tint
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val rect = RectF()

    /** 角标数字（标签页数量用） */
    var badge: String? = null
        set(v) { field = v; invalidate() }

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = min(w, h)
        val cx = w / 2f; val cy = h / 2f
        val r = s * 0.34f
        paint.strokeWidth = s * strokeWidthFactor
        fill.color = tint
        path.reset()

        when (glyph) {
            Glyph.BACK -> {
                path.moveTo(cx + r * 0.45f, cy - r * 0.72f)
                path.lineTo(cx - r * 0.45f, cy)
                path.lineTo(cx + r * 0.45f, cy + r * 0.72f)
                canvas.drawPath(path, paint)
            }
            Glyph.FORWARD -> {
                path.moveTo(cx - r * 0.45f, cy - r * 0.72f)
                path.lineTo(cx + r * 0.45f, cy)
                path.lineTo(cx - r * 0.45f, cy + r * 0.72f)
                canvas.drawPath(path, paint)
            }
            Glyph.RELOAD -> {
                rect.set(cx - r * 0.78f, cy - r * 0.78f, cx + r * 0.78f, cy + r * 0.78f)
                canvas.drawArc(rect, 40f, 280f, false, paint)
                path.moveTo(cx + r * 0.78f, cy - r * 0.62f)
                path.lineTo(cx + r * 0.86f, cy - r * 0.12f)
                path.lineTo(cx + r * 0.36f, cy - r * 0.3f)
                canvas.drawPath(path, paint)
            }
            Glyph.CLOSE -> {
                canvas.drawLine(cx - r * 0.6f, cy - r * 0.6f, cx + r * 0.6f, cy + r * 0.6f, paint)
                canvas.drawLine(cx + r * 0.6f, cy - r * 0.6f, cx - r * 0.6f, cy + r * 0.6f, paint)
            }
            Glyph.PLUS -> {
                canvas.drawLine(cx - r * 0.65f, cy, cx + r * 0.65f, cy, paint)
                canvas.drawLine(cx, cy - r * 0.65f, cx, cy + r * 0.65f, paint)
            }
            Glyph.TABS -> {
                rect.set(cx - r * 0.78f, cy - r * 0.78f, cx + r * 0.5f, cy + r * 0.5f)
                canvas.drawRoundRect(rect, r * 0.22f, r * 0.22f, paint)
                rect.set(cx - r * 0.35f, cy - r * 0.35f, cx + r * 0.82f, cy + r * 0.82f)
                val a = paint.alpha
                paint.alpha = (a * 0.55f).toInt()
                canvas.drawRoundRect(rect, r * 0.22f, r * 0.22f, paint)
                paint.alpha = a
            }
            Glyph.MENU -> {
                val y = listOf(-0.55f, 0f, 0.55f)
                for (k in y) canvas.drawLine(cx - r * 0.7f, cy + r * k, cx + r * 0.7f, cy + r * k, paint)
            }
            Glyph.HOME -> {
                path.moveTo(cx - r * 0.82f, cy - r * 0.02f)
                path.lineTo(cx, cy - r * 0.78f)
                path.lineTo(cx + r * 0.82f, cy - r * 0.02f)
                canvas.drawPath(path, paint)
                path.reset()
                path.moveTo(cx - r * 0.58f, cy + r * 0.05f)
                path.lineTo(cx - r * 0.58f, cy + r * 0.75f)
                path.lineTo(cx + r * 0.58f, cy + r * 0.75f)
                path.lineTo(cx + r * 0.58f, cy + r * 0.05f)
                canvas.drawPath(path, paint)
            }
            Glyph.SHIELD -> {
                path.moveTo(cx, cy - r * 0.85f)
                path.lineTo(cx + r * 0.72f, cy - r * 0.52f)
                path.lineTo(cx + r * 0.62f, cy + r * 0.35f)
                path.lineTo(cx, cy + r * 0.85f)
                path.lineTo(cx - r * 0.62f, cy + r * 0.35f)
                path.lineTo(cx - r * 0.72f, cy - r * 0.52f)
                path.close()
                canvas.drawPath(path, paint)
            }
            Glyph.SHARE -> {
                canvas.drawLine(cx, cy + r * 0.8f, cx, cy - r * 0.4f, paint)
                path.moveTo(cx - r * 0.45f, cy - r * 0.02f)
                path.lineTo(cx, cy - r * 0.5f)
                path.lineTo(cx + r * 0.45f, cy - r * 0.02f)
                canvas.drawPath(path, paint)
                canvas.drawLine(cx - r * 0.6f, cy + r * 0.35f, cx - r * 0.6f, cy + r * 0.8f, paint)
                canvas.drawLine(cx - r * 0.6f, cy + r * 0.8f, cx + r * 0.6f, cy + r * 0.8f, paint)
                canvas.drawLine(cx + r * 0.6f, cy + r * 0.8f, cx + r * 0.6f, cy + r * 0.35f, paint)
            }
            Glyph.SETTINGS -> {
                canvas.drawCircle(cx, cy, r * 0.34f, paint)
                canvas.drawCircle(cx, cy, r * 0.78f, paint)
                val a = paint.alpha; paint.alpha = (a * 0.5f).toInt()
                for (i in 0 until 8) {
                    val ang = Math.toRadians(i * 45.0)
                    val x1 = cx + (r * 0.78f) * Math.cos(ang).toFloat()
                    val y1 = cy + (r * 0.78f) * Math.sin(ang).toFloat()
                    val x2 = cx + r * Math.cos(ang).toFloat()
                    val y2 = cy + r * Math.sin(ang).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, paint)
                }
                paint.alpha = a
            }
            Glyph.STAR -> {
                for (i in 0 until 10) {
                    val ang = Math.toRadians(-90.0 + i * 36.0)
                    val rr = if (i % 2 == 0) r * 0.85f else r * 0.36f
                    val x = cx + rr * Math.cos(ang).toFloat()
                    val y = cy + rr * Math.sin(ang).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                canvas.drawPath(path, paint)
            }
            Glyph.SEARCH -> {
                canvas.drawCircle(cx - r * 0.12f, cy - r * 0.12f, r * 0.55f, paint)
                canvas.drawLine(cx + r * 0.3f, cy + r * 0.3f, cx + r * 0.8f, cy + r * 0.8f, paint)
            }
            Glyph.CHEVRON_DOWN -> {
                path.moveTo(cx - r * 0.55f, cy - r * 0.24f)
                path.lineTo(cx, cy + r * 0.28f)
                path.lineTo(cx + r * 0.55f, cy - r * 0.24f)
                canvas.drawPath(path, paint)
            }
            Glyph.CHEVRON_UP -> {
                path.moveTo(cx - r * 0.55f, cy + r * 0.24f)
                path.lineTo(cx, cy - r * 0.28f)
                path.lineTo(cx + r * 0.55f, cy + r * 0.24f)
                canvas.drawPath(path, paint)
            }
            Glyph.LOCK -> {
                rect.set(cx - r * 0.5f, cy - r * 0.1f, cx + r * 0.5f, cy + r * 0.7f)
                canvas.drawRoundRect(rect, r * 0.16f, r * 0.16f, paint)
                rect.set(cx - r * 0.34f, cy - r * 0.62f, cx + r * 0.34f, cy + r * 0.1f)
                canvas.drawArc(rect, 180f, 180f, false, paint)
            }
            Glyph.GLOBE -> {
                canvas.drawCircle(cx, cy, r * 0.78f, paint)
                rect.set(cx - r * 0.36f, cy - r * 0.78f, cx + r * 0.36f, cy + r * 0.78f)
                canvas.drawOval(rect, paint)
                canvas.drawLine(cx - r * 0.78f, cy, cx + r * 0.78f, cy, paint)
            }
            Glyph.HISTORY -> {
                canvas.drawCircle(cx, cy, r * 0.76f, paint)
                canvas.drawLine(cx, cy, cx, cy - r * 0.42f, paint)
                canvas.drawLine(cx, cy, cx + r * 0.34f, cy + r * 0.2f, paint)
            }
            Glyph.DOWNLOAD -> {
                canvas.drawLine(cx, cy - r * 0.7f, cx, cy + r * 0.35f, paint)
                path.moveTo(cx - r * 0.42f, cy - r * 0.02f)
                path.lineTo(cx, cy + r * 0.42f)
                path.lineTo(cx + r * 0.42f, cy - r * 0.02f)
                canvas.drawPath(path, paint)
                canvas.drawLine(cx - r * 0.6f, cy + r * 0.78f, cx + r * 0.6f, cy + r * 0.78f, paint)
            }
            Glyph.FIND -> {
                canvas.drawCircle(cx - r * 0.1f, cy - r * 0.1f, r * 0.5f, paint)
                canvas.drawLine(cx + r * 0.28f, cy + r * 0.28f, cx + r * 0.72f, cy + r * 0.72f, paint)
                canvas.drawLine(cx - r * 0.32f, cy - r * 0.1f, cx + r * 0.12f, cy - r * 0.1f, paint)
            }
        }

        badge?.let { text ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Theme.ACCENT; textSize = s * 0.3f; typeface = Theme.bold
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(text, cx, cy + s * 0.11f, p)
        }
    }
}
