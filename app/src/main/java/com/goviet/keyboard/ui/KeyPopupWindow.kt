package com.goviet.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow
import com.goviet.core.density

class KeyPopupWindow(private val context: Context) {
    private val popupWindow = PopupWindow(context).apply {
        setBackgroundDrawable(null)
        isOutsideTouchable = true
        isFocusable = false
        isClippingEnabled = false
        animationStyle = 0
    }

    private val popupView = PopupView(context)

    init {
        popupWindow.contentView = popupView
    }

    enum class Mode {
        PREVIEW,
        LONG_PRESS
    }

    private var currentMode: Mode = Mode.PREVIEW
    private var activeOptions: List<String> = emptyList()
    private var popupWidthPx = 0
    private var popupHeightPx = 0

    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE

    private data class PopupLayout(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )

    private val locationBuf = IntArray(2)

    private fun anchorPopup(
        anchorView: View,
        keyRect: RectF?,
        widthDp: Int,
        heightDp: Int
    ): PopupLayout {
        val density = context.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()

        anchorView.getLocationInWindow(locationBuf)
        val anchorX = if (keyRect != null) {
            locationBuf[0] + keyRect.centerX()
        } else {
            locationBuf[0] + anchorView.width / 2f
        }

        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val margin = (8 * density).toInt()

        val left = (anchorX - width / 2f).coerceIn(
            margin.toFloat(),
            maxOf(margin.toFloat(), (screenWidth - margin - width).toFloat())
        )

        val x = left.toInt()
        val yRaw = if (keyRect != null) {
            locationBuf[1] + keyRect.top - height - 6f * density
        } else {
            locationBuf[1] - height - 6f * density
        }
        val y = yRaw.toInt().coerceIn(margin, maxOf(margin, screenHeight - margin - height))

        return PopupLayout(x, y, width, height)
    }

    fun showPreview(
        anchorView: View,
        label: String,
        isDark: Boolean,
        theme: KeyboardTheme,
        keyRect: RectF? = null
    ) {
        currentMode = Mode.PREVIEW
        activeOptions = emptyList()
        popupView.setPreviewData(label, isDark, theme)

        val layout = anchorPopup(anchorView, keyRect, widthDp = 64, heightDp = 64)
        val (x, y, width, height) = layout

        val wasShowing = popupWindow.isShowing
        val oldX = lastX
        val oldY = lastY
        val oldWidth = popupWindow.width
        val oldHeight = popupWindow.height

        popupWindow.width = width
        popupWindow.height = height
        lastX = x
        lastY = y
        popupWidthPx = width
        popupHeightPx = height

        if (wasShowing) {
            if (oldX != x || oldY != y || oldWidth != width || oldHeight != height) {
                popupWindow.update(x, y, width, height)
            }
        } else {
            popupWindow.showAtLocation(anchorView.rootView, Gravity.NO_GRAVITY, x, y)
        }
    }

    fun showLongPress(
        anchorView: View,
        options: List<String>,
        hoveredIdx: Int,
        isDark: Boolean,
        theme: KeyboardTheme,
        keyRect: RectF? = null
    ) {
        currentMode = Mode.LONG_PRESS
        activeOptions = options

        val numCols = getNumCols(options.size)
        val numRows = getNumRows(options.size)
        val (widthDp, heightDp) = when {
            options.size <= 1 -> Pair(64, 64)
            numRows == 1 -> Pair(numCols * 44 + 8, 52)
            numRows == 2 -> Pair(numCols * 44 + 8, 96)
            else -> Pair(numCols * 44 + 8, 140)
        }

        val layout = anchorPopup(anchorView, keyRect, widthDp, heightDp)
        popupView.setLongPressData(options, hoveredIdx, isDark, theme, numCols, numRows)

        val wasShowing = popupWindow.isShowing
        val oldX = lastX
        val oldY = lastY
        val oldWidth = popupWindow.width
        val oldHeight = popupWindow.height

        popupWindow.width = layout.width
        popupWindow.height = layout.height
        lastX = layout.x
        lastY = layout.y
        popupWidthPx = layout.width
        popupHeightPx = layout.height

        if (wasShowing) {
            if (oldX != layout.x || oldY != layout.y || oldWidth != layout.width || oldHeight != layout.height) {
                popupWindow.update(layout.x, layout.y, layout.width, layout.height)
            }
        } else {
            popupWindow.showAtLocation(anchorView.rootView, Gravity.NO_GRAVITY, layout.x, layout.y)
        }
    }

    fun hoverIndexForScreen(screenX: Float, screenY: Float, baseIdx: Int): Int {
        if (currentMode != Mode.LONG_PRESS || activeOptions.size <= 1) {
            if (activeOptions.isEmpty()) return 0
            return baseIdx.coerceIn(0, activeOptions.size - 1)
        }
        val numCols = getNumCols(activeOptions.size)
        val numRows = getNumRows(activeOptions.size)

        val density = context.density
        val padding = 4f * density
        val contentLeft = lastX + padding
        val contentTop = lastY + padding
        val contentWidth = popupWidthPx - padding * 2
        val contentHeight = popupHeightPx - padding * 2

        if (contentWidth <= 0 || contentHeight <= 0) return baseIdx.coerceIn(0, activeOptions.size - 1)

        val colWidth = contentWidth / numCols
        val rowHeight = contentHeight / numRows

        val relX = screenX - contentLeft
        val relY = screenY - contentTop

        val col = (relX / colWidth).toInt().coerceIn(0, numCols - 1)
        val row = (relY / rowHeight).toInt().coerceIn(0, numRows - 1)

        val index = row * numCols + col
        return index.coerceIn(0, activeOptions.size - 1)
    }

    fun trackHoverForScreen(screenX: Float, screenY: Float, baseIdx: Int, currentIdx: Int): Int {
        val idx = hoverIndexForScreen(screenX, screenY, baseIdx)
        if (idx != currentIdx) {
            updateHoverIndex(idx)
        }
        return idx
    }

    fun trackHoverForScreenX(screenX: Float, baseIdx: Int, currentIdx: Int): Int {
        return trackHoverForScreen(screenX, lastY + popupHeightPx / 2f, baseIdx, currentIdx)
    }

    fun updateHoverIndex(index: Int) {
        if (currentMode == Mode.LONG_PRESS) {
            popupView.updateHoverIndex(index)
        }
    }

    fun dismiss() {
        if (popupWindow.isShowing) {
            popupWindow.dismiss()
        }
    }

    companion object {
        fun getNumCols(size: Int): Int = when {
            size <= 1 -> 1
            size <= 6 -> size
            else -> 6
        }

        fun getNumRows(size: Int): Int = when {
            size <= 1 -> 1
            size <= 6 -> 1
            size <= 12 -> 2
            else -> 3
        }
    }

    private class PopupView(context: Context) : View(context) {
        private var mode: Mode = Mode.PREVIEW
        private var label: String = ""
        private var isDark: Boolean = false
        private lateinit var theme: KeyboardTheme

        private var options: List<String> = emptyList()
        private var hoveredIdx: Int = -1
        private var numCols: Int = 1
        private var numRows: Int = 1

        private val density get() = context.density
        private val boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = boldTypeface
        }

        private val mainRect = RectF()
        private val shadowRect1 = RectF()
        private val shadowRect2 = RectF()
        private val itemHighlightRect = RectF()
        private val cellRect = RectF()

        fun setPreviewData(label: String, isDark: Boolean, theme: KeyboardTheme) {
            this.mode = Mode.PREVIEW
            this.label = label
            this.isDark = isDark
            this.theme = theme
            invalidate()
        }

        fun setLongPressData(
            options: List<String>,
            hoveredIdx: Int,
            isDark: Boolean,
            theme: KeyboardTheme,
            numCols: Int,
            numRows: Int
        ) {
            this.mode = Mode.LONG_PRESS
            this.options = options
            this.hoveredIdx = hoveredIdx
            this.isDark = isDark
            this.theme = theme
            this.numCols = numCols
            this.numRows = numRows
            invalidate()
        }

        fun updateHoverIndex(index: Int) {
            if (this.hoveredIdx != index) {
                this.hoveredIdx = index
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!::theme.isInitialized) return

            val w = width.toFloat()
            val h = height.toFloat()

            val shadowPadding = 3f * density
            mainRect.set(shadowPadding, shadowPadding, w - shadowPadding, h - shadowPadding - 2f * density)

            shadowRect1.set(mainRect.left, mainRect.top + 3f * density, mainRect.right, mainRect.bottom + 3f * density)
            KeyRenderer.drawFlatRoundedRect(
                canvas = canvas,
                rect = shadowRect1,
                cornerRadius = 14f * density,
                color = if (isDark) 0x24000000 else 0x0F000000,
                style = Paint.Style.FILL
            )

            shadowRect2.set(mainRect.left, mainRect.top + 1.5f * density, mainRect.right, mainRect.bottom + 1.5f * density)
            KeyRenderer.drawFlatRoundedRect(
                canvas = canvas,
                rect = shadowRect2,
                cornerRadius = 14f * density,
                color = if (isDark) 0x3D000000 else 0x1A000000,
                style = Paint.Style.FILL
            )

            val surfaceVariant = theme.keyBgColor
            KeyRenderer.drawFlatRoundedRect(
                canvas = canvas,
                rect = mainRect,
                cornerRadius = 14f * density,
                color = surfaceVariant,
                style = Paint.Style.FILL
            )

            val borderColor = (theme.textColor and 0x00FFFFFF) or (0x1C shl 24)
            KeyRenderer.drawFlatRoundedRect(
                canvas = canvas,
                rect = mainRect,
                cornerRadius = 14f * density,
                color = borderColor,
                style = Paint.Style.STROKE,
                strokeWidth = 1f * density
            )

            if (mode == Mode.PREVIEW || (mode == Mode.LONG_PRESS && options.size <= 1)) {
                val displayText = if (mode == Mode.PREVIEW) label else (options.firstOrNull() ?: "")
                textPaint.color = theme.textColor
                textPaint.textSize = 28f * density
                textPaint.typeface = boldTypeface

                val baseline = KeyboardUtils.centerBaselineY(mainRect, textPaint)
                canvas.drawText(displayText, w / 2f, baseline, textPaint)
            } else {
                if (options.isEmpty()) return
                val contentWidth = mainRect.width()
                val contentHeight = mainRect.height()
                val colWidth = contentWidth / numCols
                val rowHeight = contentHeight / numRows

                for (idx in options.indices) {
                    val row = idx / numCols
                    val col = idx % numCols

                    val optChar = options[idx]
                    val isHovered = (idx == hoveredIdx)

                    val itemLeft = mainRect.left + col * colWidth
                    val itemRight = itemLeft + colWidth
                    val itemTop = mainRect.top + row * rowHeight
                    val itemBottom = itemTop + rowHeight

                    if (isHovered) {
                        val highlightPadding = 2f * density
                        itemHighlightRect.set(
                            itemLeft + highlightPadding,
                            itemTop + highlightPadding,
                            itemRight - highlightPadding,
                            itemBottom - highlightPadding
                        )
                        KeyRenderer.drawFlatRoundedRect(
                            canvas = canvas,
                            rect = itemHighlightRect,
                            cornerRadius = 8f * density,
                            color = theme.activeAccentColor,
                            style = Paint.Style.FILL
                        )
                    }

                    val textOnPrimary = getContrastColor(theme.activeAccentColor)
                    textPaint.color = if (isHovered) textOnPrimary else theme.textColor
                    textPaint.textSize = if (numRows > 1) 19f * density else 21f * density
                    textPaint.typeface = boldTypeface

                    cellRect.set(itemLeft, itemTop, itemRight, itemBottom)
                    val baseline = KeyboardUtils.centerBaselineY(cellRect, textPaint)
                    canvas.drawText(optChar, itemLeft + colWidth / 2f, baseline, textPaint)
                }
            }
        }

        private fun getContrastColor(color: Int): Int {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val yiq = (r * 299 + g * 587 + b * 114) / 1000
            return if (yiq >= 128) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
}
