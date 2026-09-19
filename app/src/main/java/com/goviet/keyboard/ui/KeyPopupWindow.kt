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

    // Lightweight copy of the last long-press geometry so the touch handler can
    // map the finger position to an option 1:1 instead of a fixed pixel step.
    private var activeOptions: List<String> = emptyList()
    private var popupWidthPx = 0

    // Anchor (screen key-center X) and direction of the long-press layout, used by
    // hoverIndexForScreenX and by PopupView to mirror the drawn option order.
    private var hoverAnchorX = 0f
    private var growRight = true

    // Last popup on-screen position we requested (PopupWindow has no public x/y getters).
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE

    private data class PopupPosition(val x: Int, val y: Int, val width: Int, val height: Int)

    private data class PopupLayout(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val anchorX: Float,
        val growRight: Boolean
    )

    private val locationBuf = IntArray(2)

    /**
     * Lays out the long-press popup relative to the key, growing TOWARD the screen
     * center so the popup never collides with the screen edges:
     *
     *  - Keys on the left half (e.g. 'a'): the default option sits on the LEFT,
     *    next to the key, and additional options extend rightward.
     *  - Keys on the right half (e.g. 'o'): the default option sits on the RIGHT,
     *    next to the key, and additional options extend leftward (mirrored order).
     *
     * The default option's slot is centered on [anchorX] (the key's screen center),
     * so the finger is always over the currently-selected option.
     */
    private fun computeLongPressLayout(anchorView: View, keyRect: RectF?, optionCount: Int): PopupLayout {
        val density = context.density
        val width = if (optionCount <= 1) (66 * density).toInt() else (44 * optionCount * density).toInt()
        val height = (72 * density).toInt()

        anchorView.getLocationInWindow(locationBuf)
        val anchorX = if (keyRect != null) {
            locationBuf[0] + keyRect.centerX()
        } else {
            locationBuf[0] + anchorView.width / 2f
        }

        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val margin = (8 * density).toInt()

        val growRight = anchorX < screenWidth / 2f
        val slot = width.toFloat() / optionCount.coerceAtLeast(1)

        // Center the default option's slot on the key. For growRight the whole
        // popup starts there and extends right; for growLeft it extends left.
        val anchoredLeft = if (growRight) {
            anchorX - slot / 2f
        } else {
            anchorX + slot / 2f - width
        }
        val left = anchoredLeft.coerceIn(
            margin.toFloat(),
            maxOf(margin.toFloat(), (screenWidth - margin - width).toFloat())
        )

        val x = left.toInt()
        val yRaw = if (keyRect != null) {
            locationBuf[1] + keyRect.top - height - 4f * density
        } else {
            locationBuf[1] - (70 * density)
        }
        val y = yRaw.toInt().coerceIn(margin, maxOf(margin, screenHeight - margin - height))

        return PopupLayout(x, y, width, height, anchorX, growRight)
    }

    private fun computePosition(anchorView: View, keyRect: RectF?, widthDp: Int): PopupPosition {
        val density = context.density
        val width = (widthDp * density).toInt()
        val height = (72 * density).toInt()

        anchorView.getLocationInWindow(locationBuf)

        val keyCenterX = if (keyRect != null) {
            locationBuf[0] + keyRect.centerX()
        } else {
            locationBuf[0] + anchorView.width / 2f
        }
        val idealLeft = keyCenterX - width / 2f
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val margin = (8 * density).toInt()

        val left = idealLeft.coerceIn(margin.toFloat(), maxOf(margin.toFloat(), (screenWidth - margin - width).toFloat()))

        val x = left.toInt()
        val yRaw = if (keyRect != null) {
            locationBuf[1] + keyRect.top - height - 4f * density
        } else {
            locationBuf[1] - (70 * density)
        }
        val y = yRaw.toInt().coerceIn(margin, maxOf(margin, screenHeight - margin - height))

        return PopupPosition(x, y, width, height)
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

        val (x, y, width, height) = computePosition(anchorView, keyRect, 66)

        val wasShowing = popupWindow.isShowing
        val oldX = lastX
        val oldY = lastY
        val oldWidth = popupWindow.width
        val oldHeight = popupWindow.height

        popupWindow.width = width
        popupWindow.height = height
        lastX = x
        lastY = y

        if (wasShowing) {
            // Only pay for the WindowManager.updateViewLayout round-trip (main thread,
            // mid-touch) when the preview actually moved or resized.
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
        val layout = computeLongPressLayout(anchorView, keyRect, options.size)
        hoverAnchorX = layout.anchorX
        growRight = layout.growRight
        popupView.setLongPressData(options, hoveredIdx, isDark, theme, mirrored = !layout.growRight)

        // Capture the current geometry BEFORE assigning, so the unchanged check below
        // reflects the real window state rather than the values we are about to set.
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

        if (wasShowing) {
            // Avoid a pointless WindowManager.updateViewLayout (binder round-trip on
            // the main thread while the finger is down) when geometry is unchanged.
            if (oldX != layout.x || oldY != layout.y || oldWidth != layout.width || oldHeight != layout.height) {
                popupWindow.update(layout.x, layout.y, layout.width, layout.height)
            }
        } else {
            popupWindow.showAtLocation(anchorView.rootView, Gravity.NO_GRAVITY, layout.x, layout.y)
        }
    }

    /**
     * Maps the finger's window X onto an option index using the popup's real slot
     * width, anchored at the key's screen center. The highlight therefore tracks
     * the finger 1:1 instead of jumping by a hard-coded pixel step, which feels
     * janky.
     *
     * The direction depends on where the key sits: for keys on the left half
     * (growRight) a rightward drag walks toward higher indices; for keys on the
     * right half (growLeft) a leftward drag does, i.e. the order runs opposite.
     */
    fun hoverIndexForScreenX(screenX: Float, baseIdx: Int): Int {
        if (currentMode != Mode.LONG_PRESS || activeOptions.size <= 1) {
            if (activeOptions.isEmpty()) return 0
            return baseIdx.coerceIn(0, activeOptions.size - 1)
        }
        val size = activeOptions.size
        val slot = popupWidthPx.toFloat() / size
        if (slot <= 0f) return baseIdx.coerceIn(0, size - 1)
        val slotOffset = (screenX - hoverAnchorX) / slot
        val steps = if (growRight) Math.round(slotOffset) else -Math.round(slotOffset)
        return (baseIdx + steps).coerceIn(0, size - 1)
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

    private class PopupView(context: Context) : View(context) {
        private var mode: Mode = Mode.PREVIEW
        private var label: String = ""
        private var isDark: Boolean = false
        private lateinit var theme: KeyboardTheme

        private var options: List<String> = emptyList()
        private var hoveredIdx: Int = -1
        private var mirrored: Boolean = false

        private val density get() = context.density
        private val boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = boldTypeface
        }

        // Preallocated RectFs to avoid any allocation in onDraw
        private val mainRect = RectF()
        private val shadowRect1 = RectF()
        private val shadowRect2 = RectF()
        private val itemHighlightRect = RectF()

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
            mirrored: Boolean
        ) {
            this.mode = Mode.LONG_PRESS
            this.options = options
            this.hoveredIdx = hoveredIdx
            this.isDark = isDark
            this.theme = theme
            this.mirrored = mirrored
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

            val shadowPadding = 4f * density
            mainRect.set(shadowPadding, shadowPadding, w - shadowPadding, h - shadowPadding - 2f * density)

            shadowRect1.set(mainRect.left, mainRect.top + 3f * density, mainRect.right, mainRect.bottom + 3f * density)
            KeyRenderer.drawFlatRoundedRect(
                canvas = canvas,
                rect = shadowRect1,
                cornerRadius = 12f * density,
                color = if (isDark) 0x24000000 else 0x0F000000,
                style = Paint.Style.FILL
            )

            shadowRect2.set(mainRect.left, mainRect.top + 1.5f * density, mainRect.right, mainRect.bottom + 1.5f * density)
            KeyRenderer.drawFlatRoundedRect(
                canvas = canvas,
                rect = shadowRect2,
                cornerRadius = 12f * density,
                color = if (isDark) 0x3D000000 else 0x1A000000,
                style = Paint.Style.FILL
            )

            val surfaceVariant = theme.keyBgColor
            KeyRenderer.drawFlatRoundedRect(
                canvas = canvas,
                rect = mainRect,
                cornerRadius = 12f * density,
                color = surfaceVariant,
                style = Paint.Style.FILL
            )

            val borderColor = (theme.textColor and 0x00FFFFFF) or (0x1C shl 24)
            KeyRenderer.drawFlatRoundedRect(
                canvas = canvas,
                rect = mainRect,
                cornerRadius = 12f * density,
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
                val optionWidth = contentWidth / options.size
                val itemTop = mainRect.top + 3f * density
                val itemBottom = mainRect.bottom - 3f * density

                for (idx in options.indices) {
                    // For keys on the right half the option order is mirrored so the
                    // default option ends up on the right, next to the key, while the
                    // rest extend leftward toward the screen center.
                    val originalIdx = if (mirrored) (options.size - 1 - idx) else idx
                    val optChar = options[originalIdx]
                    val isHovered = (originalIdx == hoveredIdx)

                    val itemLeft = mainRect.left + idx * optionWidth
                    val itemRight = itemLeft + optionWidth

                    if (isHovered) {
                        val highlightPadding = 2f * density
                        itemHighlightRect.set(
                            itemLeft + highlightPadding,
                            itemTop,
                            itemRight - highlightPadding,
                            itemBottom
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
                    textPaint.textSize = 18f * density
                    textPaint.typeface = boldTypeface

                    val baseline = KeyboardUtils.centerBaselineY(mainRect, textPaint)
                    canvas.drawText(optChar, itemLeft + optionWidth / 2f, baseline, textPaint)
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
