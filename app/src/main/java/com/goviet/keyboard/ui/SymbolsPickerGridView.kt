package com.goviet.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.goviet.keyboard.VietnameseInputMethodService
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SymbolsPickerGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyGridView(context, attrs, defStyleAttr) {

    // Inputs/setters
    /**
     * The symbols and the control row, as nodes for a screen reader.
     *
     * Scrolling moves the symbol cells, and switching tabs replaces them, so
     * this is asked for fresh on every call and [invalidateRoot] is called
     * whenever the grid moves.
     */
    private val accessibility = KeyGridTouchHelper(this) { accessibilityCells() }

    var service: VietnameseInputMethodService? = null
    var activeTab: Int = 0
        set(value) {
            if (field != value) {
                field = value
                // A tab is a different list of symbols, so it starts at the top.
                scrollToTop()
                invalidate()
            }
        }
    var onTabChange: ((Int) -> Unit)? = null
    var onKey: ((String) -> Unit)? = null

    // State sync
    var symbolsList: List<String> = emptyList()
        set(value) {
            val changed = field != value
            field = value
            // The list can change without the view being re-measured (switching
            // tab), so the row count has to follow it here, not only on layout.
            if (changed) {
                updateGridMetrics()
                // Different symbols are different keys, not the same keys
                // somewhere else: a screen reader still holding the old nodes
                // would read out symbols this tab does not have.
                accessibility.invalidateRoot()
            }
            invalidate()
        }

    var currentImeOptions: Int = 0
        set(value) {
            if (field != value) {
                field = value
                setupBottomKeys()
                requestLayoutAndCalculate()
            }
        }

    var currentInputType: Int = 0
        set(value) {
            if (field != value) {
                field = value
                setupBottomKeys()
                requestLayoutAndCalculate()
            }
        }

    var currentLanguageMode: String = "VIE"
        set(value) {
            field = value
            invalidate()
        }

    // Grid details.
    //
    // Seven columns, not eight, and that is a touch target decision rather than
    // a taste one: on a 360dp panel a cell is width/cols, so eight columns is
    // 45dp — under the 48dp a thumb can hit. The cells touch each other, so a
    // cell cannot grow its target into its neighbour's: a 48dp target in a grid
    // with no gaps would mean two keys claiming the same pixel. Seven columns is
    // 51.4dp, and the 27 symbols of the 1?# tab still fit in four rows.
    internal val cols = 7

    // Grid viewport, shared by drawing and hit-testing so the two can never
    // disagree about where the grid ends. These replaced a hardcoded
    // "height - 66dp" bottom-row edge that only happened to line up.
    // Internal rather than private so the reachability test can assert against
    // the real geometry instead of a reimplementation of it.
    internal var gridTop = 0f
    internal var gridBottom = 0f
    internal var cellSize = 0f
    internal var rowCount = 0
    internal var maxScrollOffset = 0f
    internal var scrollOffset = 0f
    private val scroller = OverScroller(context)

    // Bottom control row keys
    private val bottomKeys = mutableListOf<Key>()

    // Touch variables
    private var activeTouchedBottomKey: Key? = null
    private var pressedSymbolIndex = -1
    private var isTouchInGrid = false
    // A drag in the grid is either a scroll or a walk across the tab strip;
    // whichever axis the finger commits to first wins.
    private var axisLocked = false
    private var isVerticalScroll = false
    private var downX = 0f
    private var downY = 0f
    private var lastTouchY = 0f
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity

    // Preallocated drawing structures to prevent GC in onDraw
    private val symbolCardRect = RectF()
    private val symbolVisualDrawRect = RectF()

    // Backspace loop
    private val backspaceRepeatHandler = RepeatingKeyPressHandler {
        onKey?.invoke("BACKSPACE")
    }

    /**
     * Every symbol the panel can put a finger on right now, in the order they
     * are drawn, plus the control row beneath them.
     *
     * The cell is the card that is drawn — the symbol's square inset by the
     * card padding — so a screen reader draws its frame where the symbol is,
     * and the same 1.5dp padding the draw uses rather than a second guess at
     * where the padding is.
     *
     * Only the rows the viewport shows are listed. A row scrolled out of view
     * is not on screen, and a screen reader reading a panel that scrolls
     * announces what it walks into; listing it and then not drawing it would
     * be a node that leads nowhere.
     */
    /**
     * The symbols the viewport shows, in the order they are drawn.
     *
     * Shared with [onDraw] so the nodes are the drawn cells rather than a second
     * window over the same grid: two copies of this would be free to drift, and
     * a node set that has drifted points a screen reader at an empty spot.
     */
    internal fun visibleSymbolIndices(): IntRange {
        if (cellSize <= 0f || rowCount <= 0) return 0 until 0
        val firstRow = max(0, (scrollOffset / cellSize).toInt())
        val lastRow = min(rowCount - 1, ((gridBottom - gridTop + scrollOffset) / cellSize).toInt())
        return (firstRow * cols) until min(symbolsList.size, (lastRow + 1) * cols)
    }

    internal fun accessibilityCells(): List<KeyGridCell> {
        if (width <= 0 || height <= 0 || cellSize <= 0f) return emptyList()
        val cells = mutableListOf<KeyGridCell>()
        val cardPadding = 1.5f * density

        for (index in visibleSymbolIndices()) {
            val symbol = symbolsList[index]
            val cellLeft = (index % cols) * cellSize
            val cellTop = gridTop + (index / cols) * cellSize - scrollOffset
            cells.add(
                KeyGridCell(
                    bounds = RectF(
                        cellLeft + cardPadding,
                        cellTop + cardPadding,
                        cellLeft + cellSize - cardPadding,
                        cellTop + cellSize - cardPadding
                    ),
                    description = symbol,
                    onActivate = { onKey?.invoke(symbol) }
                )
            )
        }

        for (key in bottomKeys) {
            cells.add(
                KeyGridCell(
                    bounds = RectF(key.visualRect),
                    description = KeyboardUtils.keyNodeDescription(context, key, currentImeOptions, currentInputType),
                    onActivate = { onKey?.invoke(key.code) }
                )
            )
        }
        return cells
    }

    /** Forwards a screen reader's hover to the symbols and the control row. */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    init {
        accessibility.attach()
        setupBottomKeys()
    }

    private fun setupBottomKeys() {
        // Same row the emoji panel draws, from the same spec.
        val enterLabel = KeyboardUtils.getEnterSymbolLabel(currentImeOptions, currentInputType)
        bottomKeys.clear()
        bottomKeys.addAll(BottomBarSpec.forSymbols(enterLabel = enterLabel).buildKeys())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateKeyCoordinates(w, h)
        scrollToTop()
    }

    private fun requestLayoutAndCalculate() {
        if (width > 0 && height > 0) {
            calculateKeyCoordinates(width, height)
        }
        invalidate()
    }

    private fun scrollToTop() {
        scrollOffset = 0f
        scroller.abortAnimation()
    }

    private fun clampScrollOffset() {
        val before = scrollOffset
        scrollOffset = if (maxScrollOffset > 0f && cellSize > 0f) {
            scrollOffset.coerceIn(0f, maxScrollOffset)
        } else {
            0f
        }
        // The cells moved, so the nodes describing them are out of date: a
        // screen reader would send its finger to where a symbol used to be.
        if (before != scrollOffset) accessibility.invalidateRoot()
    }

    private fun calculateKeyCoordinates(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        // Bottom row from the shared spec, so it matches the emoji panel.
        val topOfBottomRow = BottomBarSpec.forSymbols(
            enterLabel = KeyboardUtils.getEnterSymbolLabel(currentImeOptions, currentInputType)
        ).layOut(
            keys = bottomKeys,
            widthPx = width,
            heightPx = height,
            density = density,
            rowCount = BottomBarSpec.ROW_COUNT,
            verticalSpacingPx = 7.0f * density
        )

        // Grid viewport runs from the top of the panel to just above the
        // control row. One cell size drives both drawing and hit-testing.
        gridTop = 2f * density
        gridBottom = topOfBottomRow - 2f * density
        cellSize = width / cols.toFloat()
        updateGridMetrics()
    }

    internal fun updateGridMetrics() {
        rowCount = (symbolsList.size + cols - 1) / cols
        maxScrollOffset = if (cellSize > 0f) {
            (rowCount * cellSize - (gridBottom - gridTop)).coerceAtLeast(0f)
        } else {
            0f
        }
        clampScrollOffset()
    }

    /**
     * Which symbol sits under the finger, in viewport coordinates.
     * Returns -1 for the dead space below a short list, outside the grid, or
     * on a cell that has no symbol in it.
     *
     * A cell is [cellSize] square, 51.4dp on a 360dp panel, and narrower than
     * that on a split keyboard, so the cell answers the target as well. The
     * viewport bounds stay exact, so the control row below the grid is never
     * claimed by a symbol.
     */
    internal fun findSymbolIndexAt(x: Float, y: Float): Int {
        if (symbolsList.isEmpty() || cellSize <= 0f) return -1
        if (y < gridTop || y > gridBottom) return -1
        val minTouch = KeyGeometry.minTouchPx(density)

        val col = KeyGeometry.nearestCellIndex(
            position = x,
            origin = 0f,
            cellSize = cellSize,
            cellCount = cols,
            minTouchPx = minTouch
        )
        val row = KeyGeometry.nearestCellIndex(
            position = y - gridTop + scrollOffset,
            origin = 0f,
            cellSize = cellSize,
            cellCount = rowCount,
            minTouchPx = minTouch
        )
        if (col < 0 || row < 0) return -1
        val index = row * cols + col
        return if (index in symbolsList.indices) index else -1
    }

    private fun findBottomKeyByCoordinates(x: Float, y: Float): Key? =
        findKeyAt(bottomKeys, x, y, KeyGeometry.minTouchPx(density))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Symbol grid. The list scrolls, so the panel shows whatever the
        // header tab holds instead of a fixed 24 cells with the tail cut off.
        val clipTop = gridTop
        val clipBottom = gridBottom
        if (cellSize > 0f && rowCount > 0) {
            val saved = canvas.save()
            canvas.clipRect(0f, clipTop, width.toFloat(), clipBottom)
            if (scrollOffset > 0f) {
                canvas.translate(0f, -scrollOffset)
            }
            for (i in visibleSymbolIndices()) {
                val c = i % cols
                val r = i / cols
                val sym = symbolsList[i]

                val cellLeft = c * cellSize
                val cellTop = gridTop + r * cellSize

                val cardPadding = 1.5f * density
                symbolCardRect.set(
                    cellLeft + cardPadding,
                    cellTop + cardPadding,
                    cellLeft + cellSize - cardPadding,
                    cellTop + cellSize - cardPadding
                )

                val isCellPressed = (i == pressedSymbolIndex)
                val shouldDrawBg = isCellPressed || (keyStyle == 0 || keyStyle == 1)
                if (shouldDrawBg) {
                    val cardColor = if (isCellPressed) {
                        keyPressedBgColor
                    } else {
                        if (isDark) 0xFF2E3544.toInt() else 0xFFFFFFFF.toInt()
                    }
                    KeyRenderer.drawFlatRoundedRect(
                        canvas = canvas,
                        rect = symbolCardRect,
                        cornerRadius = 6f * density,
                        color = cardColor,
                        style = Paint.Style.FILL
                    )
                }

                textPaint.textSize = 19f * density
                textPaint.typeface = boldTypeface
                textPaint.color = textColor
                val baseline = KeyboardUtils.centerBaselineY(symbolCardRect, textPaint)
                canvas.drawText(sym, symbolCardRect.centerX(), baseline, textPaint)
            }
            canvas.restoreToCount(saved)
        }

        // 2. Draw Bottom Control Keys (same frame as every Key-based panel)
        bottomKeys.forEach { key ->
            computeScaledRect(
                cx = key.visualRect.centerX(),
                cy = key.visualRect.centerY(),
                w = key.visualRect.width(),
                h = key.visualRect.height(),
                scale = if (key.isPressed) 0.96f else 1.0f
            )
            drawKeyBackgroundScaled(canvas, key)

            textPaint.color = textColor
            val isSingleChar = key.label.length == 1
            if (key.isFunctional) {
                textPaint.textSize = 13f * density
                textPaint.typeface = boldTypeface
            } else if (key.isSpecialEnter && !isSingleChar) {
                textPaint.textSize = 15f * density
                textPaint.typeface = boldTypeface
            } else {
                textPaint.textSize = 21f * density
                textPaint.typeface = boldTypeface
            }

            // Reposition text drawing area
            val scale = if (key.isPressed) 0.96f else 1.0f
            val w = key.rect.width()
            val h = key.rect.height()
            val cx = key.rect.centerX()
            val cy = key.rect.centerY()
            symbolVisualDrawRect.set(
                cx - w * scale / 2f,
                cy - h * scale / 2f,
                cx + w * scale / 2f,
                cy + h * scale / 2f
            )

            val baseline = KeyboardUtils.centerBaselineY(symbolVisualDrawRect, textPaint)
            if (key.code == "ENTER") {
                val enterColor = 0xFFFFFFFF.toInt()
                KeyboardUtils.drawEnterIcon(canvas, symbolVisualDrawRect, currentImeOptions, currentInputType, density, enterColor)
            } else if (key.code == "SPACE") {
                val spaceText = if (currentLanguageMode == "VIE") "Tiếng Việt" else "English"
                textPaint.textSize = 12.5f * density
                textPaint.color = subTextColor
                textPaint.typeface = normalTypeface
                val spaceBaseline = KeyboardUtils.centerBaselineY(symbolVisualDrawRect, textPaint)
                canvas.drawText(spaceText, symbolVisualDrawRect.centerX(), spaceBaseline, textPaint)
            } else {
                canvas.drawText(key.label, symbolVisualDrawRect.centerX(), baseline, textPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val x = event.x
        val y = event.y

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                lastTouchY = y
                axisLocked = false
                isVerticalScroll = false
                isTouchInGrid = false
                activeTouchedBottomKey = null
                pressedSymbolIndex = -1
                backspaceRepeatHandler.stop()
                scroller.abortAnimation()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }

                val key = findBottomKeyByCoordinates(x, y)
                if (key != null) {
                    activeTouchedBottomKey = key
                    key.isPressed = true

                    if (key.code == "BACKSPACE") {
                        onKey?.invoke("BACKSPACE")
                        backspaceRepeatHandler.start()
                    }
                } else if (y <= gridBottom) {
                    isTouchInGrid = true
                    pressedSymbolIndex = findSymbolIndexAt(x, y)
                }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)

                if (isTouchInGrid && !axisLocked &&
                    max(abs(x - downX), abs(y - downY)) > touchSlop
                ) {
                    axisLocked = true
                    isVerticalScroll = abs(y - downY) >= abs(x - downX)
                    // Either way the pressed cell is gone: the finger has
                    // stopped pointing at one.
                    pressedSymbolIndex = -1
                    lastTouchY = y
                }

                if (isTouchInGrid && isVerticalScroll) {
                    scrollOffset = (scrollOffset - (y - lastTouchY)).coerceIn(0f, maxScrollOffset)
                    accessibility.invalidateRoot()
                    lastTouchY = y
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isVerticalScroll && maxScrollOffset > 0f) {
                    velocityTracker?.let { tracker ->
                        tracker.computeCurrentVelocity(1000)
                        val velocityY = -tracker.yVelocity
                        if (abs(velocityY) > minimumFlingVelocity) {
                            scroller.fling(
                                0,
                                scrollOffset.toInt(),
                                0,
                                velocityY.toInt(),
                                0,
                                0,
                                0,
                                maxScrollOffset.toInt()
                            )
                            postInvalidateOnAnimation()
                        }
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null

                pressedSymbolIndex = -1
                backspaceRepeatHandler.stop()

                activeTouchedBottomKey?.let { key ->
                    key.isPressed = false
                    if (action == MotionEvent.ACTION_UP) {
                        when (key.code) {
                            "ABC" -> {
                                onKey?.invoke("ABC")
                            }
                            "EMOJI" -> {
                                onKey?.invoke("EMOJI")
                            }
                            "BACKSPACE" -> {
                                // Handled on down
                            }
                            else -> {
                                onKey?.invoke(key.code)
                            }
                        }
                    }
                }

                // A drag scrolled or changed tab, so it must not also insert.
                if (isTouchInGrid && action == MotionEvent.ACTION_UP) {
                    val deltaX = x - downX
                    when {
                        axisLocked && !isVerticalScroll && abs(deltaX) > 40f * density -> {
                            onTabChange?.invoke(
                                if (deltaX > 0) (activeTab - 1).coerceAtLeast(0)
                                else (activeTab + 1).coerceAtMost(PickerData.SYMBOLS_MAP.size)
                            )
                        }
                        !axisLocked -> {
                            val gridIdx = findSymbolIndexAt(x, y)
                            if (gridIdx != -1) {
                                val pickedString = symbolsList[gridIdx]
                                service?.addRecentSymbol(pickedString)
                                onKey?.invoke(pickedString)
                            }
                        }
                    }
                }

                activeTouchedBottomKey = null
                isTouchInGrid = false
                axisLocked = false
                isVerticalScroll = false
                invalidate()
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currY.toFloat()
            accessibility.invalidateRoot()
            clampScrollOffset()
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        backspaceRepeatHandler.stop()
        velocityTracker?.recycle()
        velocityTracker = null
        scroller.abortAnimation()
    }
}
