package com.goviet.keyboard.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Unified renderer for picker items (Emoji and Symbols):
 * - Gallery / grid styling (clean, flat, uncluttered) rather than keyboard key shadows
 * - Consistent pressed state highlight, corner radius, and text baseline
 * - Theme-aware styling derived directly from KeyboardTheme
 */
object PickerItemRenderer {

    fun drawItemCell(
        canvas: Canvas,
        rect: RectF,
        text: String,
        isPressed: Boolean,
        theme: KeyboardTheme,
        density: Float,
        isEmoji: Boolean,
        textPaint: Paint,
        fillPaint: Paint,
        keyStyle: Int = 0,
        cornerRadiusPx: Float = 6f * density
    ) {
        val shouldDrawBg = isPressed || (keyStyle == 0 || keyStyle == 1)
        if (shouldDrawBg) {
            val cardColor = if (isPressed) {
                theme.keyPressedBgColor
            } else {
                if (theme.isDark) 0xFF2E3544.toInt() else 0xFFFFFFFF.toInt()
            }
            fillPaint.color = cardColor
            fillPaint.style = Paint.Style.FILL
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, fillPaint)
        }

        val cx = rect.centerX()
        val cy = rect.centerY()

        if (isEmoji) {
            textPaint.textSize = rect.width() * 0.56f
            textPaint.typeface = Typeface.DEFAULT
            textPaint.textAlign = Paint.Align.CENTER
            val baseline = KeyboardUtils.centerBaselineY(cy, textPaint)
            canvas.drawText(text, cx, baseline, textPaint)
        } else {
            textPaint.color = theme.textColor
            textPaint.textSize = 19f * density
            textPaint.typeface = Typeface.DEFAULT_BOLD
            textPaint.textAlign = Paint.Align.CENTER
            val baseline = KeyboardUtils.centerBaselineY(rect, textPaint)
            canvas.drawText(text, cx, baseline, textPaint)
        }
    }
}
