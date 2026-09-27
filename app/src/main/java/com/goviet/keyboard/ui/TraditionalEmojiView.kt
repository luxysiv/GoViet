package com.goviet.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import kotlin.math.max
import kotlin.math.min

import com.goviet.core.density

class TraditionalEmojiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyGridView(context, attrs, defStyleAttr) {
    /**
     * The emoji and the control row, as nodes for a screen reader.
     *
     * The panel is one rectangle to a screen reader otherwise, and the emoji
     * are the whole point of the panel.
     */
    private val accessibility = KeyGridTouchHelper(this) { accessibilityCells() }



    // Properties
    var emojisList: List<String> = emptyList()
        set(value) {
            val changed = field != value
            field = value
            if (changed) {
                calculateLayout()
                // A new tab is a different set of keys, and a screen reader
                // still holding the old nodes would read out emoji this tab
                // does not have.
                accessibility.invalidateRoot()
            }
            invalidate()
        }

    var currentImeOptions: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var currentInputType: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var currentLanguageMode: String = "VIE"
        set(value) {
            field = value
            invalidate()
        }

    // Callbacks
    var onSelectEmoji: ((String) -> Unit)? = null
    var onBackToLetters: (() -> Unit)? = null
    var onSwitchToSymbols: (() -> Unit)? = null
    var onKeyPress: ((String) -> Unit)? = null

    private val bottomBar = BottomBarSpec.forEmoji(enterLabel = "⏎")

    private val keysInfo = bottomBar.buildKeys()

    // Layout values
    private var totalContentHeight = 0f
    internal var colW = 0f

    /** How many rows the current list takes, the same arithmetic as the draw. */
    internal val emojiRowCount: Int
        get() = (emojisList.size + EMOJI_COLS - 1) / EMOJI_COLS
    // Internal rather than private so the touch target test can assert against
    // the real grid instead of a reimplementation of its arithmetic.
    internal var emojiAreaLeft = 0f
    private var emojiAreaRight = 0f
    internal var emojiAreaTop = 0f
    private var emojiAreaBottom = 0f
    private var emojiAreaWidth = 0f
    private var emojiAreaHeight = 0f

    // Scrolling states
    private var scrollOffset = 0f
    private val scroller = OverScroller(context)
    private var lastTouchY = 0f
    private var isDragging = false
    private var velocityTracker: android.view.VelocityTracker? = null

    // Press states
    private var pressedBottomKeyIndex = -1
    private var pressedEmojiIndex = -1
    private val pressedEmojiRect = RectF()

    private val verticalSpacing = 7.0f * density

    /**
     * The emoji the viewport shows, in the order they are drawn, plus the
     * control row beneath them.
     *
     * The bounds are the emoji's own square inset by the same 2dp the pressed
     * emoji is inset by, so a node is framed where the emoji is and not
     * slightly larger than the glyph. Only what the viewport shows: a row
     * scrolled away is not on screen, and a screen reader that walks into an
     * off-screen node is reading a panel that is not there.
     */
    /**
     * The emoji the viewport shows, in the order they are drawn.
     *
     * Shared with [onDraw] so the nodes are the drawn cells rather than a second
     * window over the same grid: two copies of this would be free to drift, and
     * a node set that has drifted points a screen reader at an empty spot.
     */
    internal fun visibleEmojiIndices(): IntRange {
        if (colW <= 0f) return 0 until 0
        val firstRow = max(0, (scrollOffset / colW).toInt())
        val pastLastRow = ((scrollOffset + emojiAreaHeight) / colW).toInt() + 1
        return (firstRow * EMOJI_COLS) until
            min(emojisList.size, pastLastRow.coerceAtMost(emojiRowCount) * EMOJI_COLS)
    }

    internal fun accessibilityCells(): List<KeyGridCell> {
        if (width <= 0 || height <= 0 || colW <= 0f) return emptyList()
        val cells = mutableListOf<KeyGridCell>()
        val inset = 2f * density

        for (index in visibleEmojiIndices()) {
            val emoji = emojisList[index]
            val cellLeft = emojiAreaLeft + (index % EMOJI_COLS) * colW
            val cellTop = emojiAreaTop + (index / EMOJI_COLS) * colW - scrollOffset
            cells.add(
                KeyGridCell(
                    bounds = RectF(
                        cellLeft + inset,
                        cellTop + inset,
                        cellLeft + colW - inset,
                        cellTop + colW - inset
                    ),
                    description = emoji,
                    onActivate = { onSelectEmoji?.invoke(emoji) }
                )
            )
        }

        for (key in keysInfo) {
            cells.add(
                KeyGridCell(
                    bounds = RectF(key.visualRect),
                    description = KeyboardUtils.keyNodeDescription(context, key, currentImeOptions, currentInputType),
                    onActivate = { onKeyPress?.invoke(key.code) }
                )
            )
        }
        return cells
    }

    /** Forwards a screen reader's hover to the emoji and the control row. */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    init {
        accessibility.attach()
        calculateLayout()
    }

    private fun calculateLayout() {
        if (width <= 0 || height <= 0) return

        // 1. Bottom row comes from the shared spec, so it lands in the same
        // place the symbol picker puts it.
        val bottomContainerTop = bottomBar.layOut(
            keys = keysInfo,
            widthPx = width,
            heightPx = height,
            density = density,
            rowCount = BottomBarSpec.ROW_COUNT,
            verticalSpacingPx = verticalSpacing
        )

        // 2. Calculate emoji area bounds
        emojiAreaLeft = KeyGeometry.panelPaddingPx(density)
        emojiAreaRight = width - KeyGeometry.panelPaddingPx(density)
        emojiAreaTop = 6f * density
        emojiAreaBottom = bottomContainerTop - 6f * density
        emojiAreaWidth = emojiAreaRight - emojiAreaLeft
        emojiAreaHeight = emojiAreaBottom - emojiAreaTop

        colW = emojiAreaWidth / EMOJI_COLS
        val numRows = (emojisList.size + EMOJI_COLS - 1) / EMOJI_COLS
        totalContentHeight = numRows * colW

        clampScrollOffset()
    }

    private fun clampScrollOffset() {
        val maxScroll = max(0f, totalContentHeight - emojiAreaHeight)
        val before = scrollOffset
        scrollOffset = scrollOffset.coerceIn(0f, maxScroll)
        // The cells moved, so the nodes describing them are out of date: a
        // screen reader would send its finger to where an emoji used to be.
        if (before != scrollOffset) accessibility.invalidateRoot()
    }

    /**
     * Which control-row key a touch is on, or -1.
     *
     * Goes through the shared hit-test so the target is 48dp even though the
     * drawn key is narrower, and so a press and its release are judged the same
     * way — a touch that lands in the grown area has to stay valid until the
     * finger lifts, or the key never fires.
     */
    internal fun findBottomKeyIndexAt(x: Float, y: Float): Int {
        if (keysInfo.isEmpty()) return -1
        val key = findKeyAt(keysInfo, x, y, KeyGeometry.minTouchPx(density)) ?: return -1
        return keysInfo.indexOf(key)
    }

    /**
     * Which emoji a touch is on, or -1.
     *
     * A cell is [colW] square — 50.3dp on a 360dp panel, already past the
     * target — and narrower than that on a split keyboard, so the same grown
     * target rule as the keys applies. The area's own bounds stay exact, so a
     * touch in the padding or on the control row is not an emoji.
     */
    internal fun findEmojiIndexAt(x: Float, y: Float): Int {
        if (emojisList.isEmpty() || colW <= 0f) return -1
        if (y < emojiAreaTop || y > emojiAreaBottom) return -1
        val minTouch = KeyGeometry.minTouchPx(density)
        val rowCount = (emojisList.size + EMOJI_COLS - 1) / EMOJI_COLS
        val col = KeyGeometry.nearestCellIndex(
            position = x - emojiAreaLeft,
            origin = 0f,
            cellSize = colW,
            cellCount = EMOJI_COLS,
            minTouchPx = minTouch
        )
        val row = KeyGeometry.nearestCellIndex(
            position = y - emojiAreaTop + scrollOffset,
            origin = 0f,
            cellSize = colW,
            cellCount = rowCount,
            minTouchPx = minTouch
        )
        if (col < 0 || row < 0) return -1
        val index = row * EMOJI_COLS + col
        return if (index in emojisList.indices) index else -1
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateLayout()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currY.toFloat()
            accessibility.invalidateRoot()
            clampScrollOffset()
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(panelBgColor)

        // 1. Draw Emojis (Clipped to the emoji area and scrolled)
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), emojiAreaBottom)
        canvas.translate(0f, -scrollOffset)

        textPaint.textSize = colW * 0.55f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.DEFAULT

        for (index in visibleEmojiIndices()) {
            val row = index / EMOJI_COLS
            val col = index % EMOJI_COLS
            val cellTop = emojiAreaTop + row * colW
            val cellBottom = cellTop + colW
            val cellCenterY = (cellTop + cellBottom) / 2f
            val baseline = KeyboardUtils.centerBaselineY(cellCenterY, textPaint)

            val emoji = emojisList[index]
            val cellLeft = emojiAreaLeft + col * colW
            val cellRight = cellLeft + colW
            val cellCenterX = (cellLeft + cellRight) / 2f

            if (pressedEmojiIndex == index) {
                pressedEmojiRect.set(
                    cellLeft + 2f * density,
                    cellTop + 2f * density,
                    cellRight - 2f * density,
                    cellBottom - 2f * density
                )
                KeyRenderer.drawFlatRoundedRect(
                    canvas = canvas,
                    rect = pressedEmojiRect,
                    cornerRadius = 6f * density,
                    color = keyPressedBgColor,
                    style = Paint.Style.FILL
                )
            }

            canvas.drawText(emoji, cellCenterX, baseline, textPaint)
        }
        canvas.restore()

        // 2. Draw Bottom Keys
        for (i in keysInfo.indices) {
            val key = keysInfo[i]
            val code = key.code
            val isFunctional = code == "ABC" || code == "!?#" || code == "BACKSPACE"
            val isSpecialEnter = code == "ENTER"

            val isPressed = (pressedBottomKeyIndex == i)

            val bgColor = if (isSpecialEnter || isFunctional) functionalKeyBgColor else keyBgColor
            val pressedBgColor = if (isSpecialEnter || isFunctional) functionalKeyPressedBgColor else keyPressedBgColor

            val textCol = if (code == "SPACE") {
                subTextColor
            } else {
                textColor
            }

            KeyRenderer.drawStandardKey(
                canvas = canvas,
                drawRect = key.rect,
                shadowRect = key.shadowRect,
                cornerRadius = 8f * density,
                density = density,
                isDark = isDark,
                keyStyle = keyStyle,
                isPressed = isPressed,
                isFunctional = isFunctional,
                isSpecialEnter = isSpecialEnter,
                bgColor = bgColor,
                pressedBgColor = pressedBgColor
            )

            val label = when (code) {
                "SPACE" -> if (currentLanguageMode == "VIE") "Tiếng Việt" else "English"
                "ENTER" -> KeyboardUtils.getEnterSymbolLabel(currentImeOptions, currentInputType)
                else -> key.label
            }

            textPaint.color = textCol
            textPaint.typeface = if (code == "SPACE") Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            textPaint.textSize = if (code == "SPACE" || code == "BACKSPACE" || code == "ENTER") 13f * density else 16f * density
            textPaint.textAlign = Paint.Align.CENTER

            val baseline = KeyboardUtils.centerBaselineY(key.rect, textPaint)
            canvas.drawText(label, key.rect.centerX(), baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (velocityTracker == null) {
            velocityTracker = android.view.VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)

        val action = event.actionMasked
        val x = event.x
        val y = event.y

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                lastTouchY = y
                isDragging = false

                if (y >= emojiAreaBottom) {
                    pressedBottomKeyIndex = findBottomKeyIndexAt(x, y)
                    pressedEmojiIndex = -1
                    invalidate()
                } else {
                    pressedBottomKeyIndex = -1
                    pressedEmojiIndex = findEmojiIndexAt(x, y)
                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaY = lastTouchY - y
                lastTouchY = y

                val touchSlop = 8f * density
                if (!isDragging && y < emojiAreaBottom) {
                    if (Math.abs(deltaY) > touchSlop) {
                        isDragging = true
                        pressedEmojiIndex = -1
                        pressedBottomKeyIndex = -1
                        invalidate()
                    }
                }

                if (isDragging) {
                    scrollOffset += deltaY
                    clampScrollOffset()
                    invalidate()
                } else {
                    if (pressedBottomKeyIndex != -1) {
                        if (findBottomKeyIndexAt(x, y) != pressedBottomKeyIndex) {
                            pressedBottomKeyIndex = -1
                            invalidate()
                        }
                    }
                    if (pressedEmojiIndex != -1) {
                        if (findEmojiIndexAt(x, y) != pressedEmojiIndex) {
                            pressedEmojiIndex = -1
                            invalidate()
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    if (pressedBottomKeyIndex != -1) {
                        if (findBottomKeyIndexAt(x, y) == pressedBottomKeyIndex) {
                            val code = keysInfo[pressedBottomKeyIndex].code
                            when (code) {
                                "ABC" -> onBackToLetters?.invoke()
                                "!?#" -> onSwitchToSymbols?.invoke()
                                "BACKSPACE" -> onKeyPress?.invoke("BACKSPACE")
                                "SPACE" -> onKeyPress?.invoke("SPACE")
                                "ENTER" -> onKeyPress?.invoke("ENTER")
                                else -> onKeyPress?.invoke(code)
                            }
                        }
                    } else if (pressedEmojiIndex != -1) {
                        val emojiIndex = findEmojiIndexAt(x, y)
                        if (emojiIndex == pressedEmojiIndex && emojiIndex in emojisList.indices) {
                            onSelectEmoji?.invoke(emojisList[emojiIndex])
                        }
                    }
                } else {
                    velocityTracker?.let { tracker ->
                        tracker.computeCurrentVelocity(1000)
                        val velocityY = tracker.yVelocity
                        val maxScroll = max(0f, totalContentHeight - emojiAreaHeight)
                        scroller.fling(
                            0, scrollOffset.toInt(),
                            0, -velocityY.toInt(),
                            0, 0,
                            0, maxScroll.toInt()
                        )
                        postInvalidateOnAnimation()
                    }
                }

                pressedBottomKeyIndex = -1
                pressedEmojiIndex = -1
                isDragging = false
                velocityTracker?.recycle()
                velocityTracker = null
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedBottomKeyIndex = -1
                pressedEmojiIndex = -1
                isDragging = false
                velocityTracker?.recycle()
                velocityTracker = null
                invalidate()
            }
        }
        return true
    }

    private companion object {
        /** Emoji per row. Was a literal 7 in four places, once per arithmetic. */
        const val EMOJI_COLS = 7
    }
}
