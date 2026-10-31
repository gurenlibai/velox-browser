package com.velox.browser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue

/** 设计令牌：全部集中在此，保证整个 App 的节奏一致。 */
object Theme {

    const val INK = 0xFF10131AL.toInt()
    const val INK_SOFT = 0xFF1A1F27.toInt()
    const val PAPER = 0xFFF7F8FAL.toInt()
    const val PAPER_SOFT = 0xFFFFFFFF.toInt()
    const val ACCENT = 0xFF14D6A4.toInt()
    const val ACCENT_DARK = 0xFF0AA57E.toInt()
    const val TEXT_ON_DARK = 0xFFF2F5F8.toInt()
    const val TEXT_SECONDARY = 0xFF5C6572.toInt()
    const val TEXT_TERTIARY = 0xFF9AA3AF.toInt()
    const val DANGER = 0xFFFF5A5F.toInt()
    const val LINE = 0x1A000000

    const val RADIUS_PILL = 28f
    const val RADIUS_CARD = 18f

    fun dp(context: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    fun dpf(context: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)

    fun pill(color: Int, radiusDp: Float, ctx: Context, stroke: Int = 0, strokeColor: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpf(ctx, radiusDp)
            setColor(color)
            if (stroke > 0) setStroke(dp(ctx, stroke.toFloat()), strokeColor)
        }

    fun glass(ctx: Context): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xF212151CL.toInt(), 0xFA0C0F15L.toInt())
    ).apply {
        cornerRadius = dpf(ctx, RADIUS_PILL)
        setStroke(dp(ctx, 1f), 0x1FFFFFFF)
    }

    fun sheet(ctx: Context): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xFF151A23L.toInt(), 0xFF0C0F15L.toInt())
    ).apply {
        cornerRadius = dpf(ctx, 26f)
    }

    val mono: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
}
