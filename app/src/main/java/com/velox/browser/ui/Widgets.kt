package com.velox.browser.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** 复用的小组件工厂，避免任何布局 XML 与反射开销。 */
object W {

    fun text(
        ctx: Context,
        s: CharSequence,
        sp: Float = 15f,
        color: Int = Theme.TEXT_ON_DARK,
        bold: Boolean = false,
        mono: Boolean = false
    ): TextView = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = when {
            mono -> Theme.mono
            bold -> Theme.bold
            else -> Theme.regular
        }
        includeFontPadding = false
    }

    fun column(ctx: Context, pad: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
    }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun tile(ctx: Context, radiusDp: Float = Theme.RADIUS_CARD, color: Int = 0x14FFFFFF): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = Theme.dpf(ctx, radiusDp)
            setColor(color)
            setStroke(Theme.dp(ctx, 1f), 0x14FFFFFF)
        }

    fun iconButton(ctx: Context, glyph: IconView.Glyph, sizeDp: Float = 44f): IconView =
        IconView(ctx).apply {
            this.glyph = glyph
            tint = Theme.TEXT_ON_DARK
            background = Theme.pill(0x1AFFFFFF, 22f, ctx)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(Theme.dp(ctx, sizeDp), Theme.dp(ctx, sizeDp))
        }

    fun divider(ctx: Context, alpha: Int = 0x14): View = View(ctx).apply {
        setBackgroundColor((alpha shl 24) or 0xFFFFFF)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(ctx, 1f)).apply {
            topMargin = Theme.dp(ctx, 8f); bottomMargin = Theme.dp(ctx, 8f)
        }
    }

    fun rippleBg(ctx: Context, radiusDp: Float = Theme.RADIUS_CARD): android.graphics.drawable.Drawable =
        android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33FFFFFF),
            null,
            GradientDrawable().apply {
                cornerRadius = Theme.dpf(ctx, radiusDp)
                setColor(android.graphics.Color.WHITE)
            }
        )
}
