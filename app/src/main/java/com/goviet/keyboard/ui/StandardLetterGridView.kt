package com.goviet.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.goviet.core.AppPreferences
import com.goviet.core.density

class StandardLetterGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyGridView(context, attrs, defStyleAttr) {

    // Properties requested by user
    var keyboardMode: String = "QWERTY"
        set(value) {
            if (field != value) {
                field = value
                internalKeyboardMode = if (value == "SYMBOLS") "SYM1" else "ABC"
                setupKeysForMode()
                requestLayoutAndCalculate()
            }
        }

    var shiftState: Int = 0
        set(value) {
            if (field != value) {
                field = value
                setupKeysForMode()
                requestLayoutAndCalculate()
            }
        }

    var languageMode: String = "VIE"
        set(value) {
            if (field != value) {
                field = value
                setupKeysForMode()
                requestLayoutAndCalculate()
            }
        }

    var imeOptions: Int = 0
        set(value) {
            if (field != value) {
                field = value
                setupKeysForMode()
                requestLayoutAndCalculate()
            }
        }

    var inputType: Int = 0
        set(value) {
            if (field != value) {
                field = value
                setupKeysForMode()
                requestLayoutAndCalculate()
            }
        }

    // Callbacks requested by user
    var onKey: ((String) -> Unit)? = null
    var onSwitchToSymbols: (() -> Unit)? = null
    var onSwitchToEmoji: (() -> Unit)? = null
    var onOpenSettings: (() -> Unit)? = null
    var onToggleLanguage: (() -> Unit)? = null
    var onOpenPopup: ((List<String>) -> Unit)? = null

    // Internal state & theme
    private var internalKeyboardMode: String = "ABC" // "ABC", "SYM1", "SYM2"

    private val keyPopup = KeyPopupWindow(context)
    private var activePopupOptionIndex = -1

    private val keys = mutableListOf<Key>()
    private var activeTouchedKey: Key? = null
    private val activePointerKeys = mutableMapOf<Int, Key>()
    private var isLongPressed = false

    private val horizontalSpacing = 2.8f * density
    private val verticalSpacing = 7.0f * density

    private var startX = 0f
    private var startY = 0f

    // Gestures for Spacebar slider & backspace deletion
    private var cursorSwipeStartX = 0f
    private var cursorLastTriggerX = 0f
    private var isCursorSwipeActive = false

    private var backspaceStartX = 0f
    private var backspaceSelectCount = 0
    private var isBackspaceSwipeActive = false
    private var hasBackspaceTriggered = false
    private val backspaceRepeatHandler = RepeatingKeyPressHandler {
        onKey?.invoke("BACKSPACE")
        hasBackspaceTriggered = true
    }

    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        activeTouchedKey?.let { key ->
            if (key.longPressOptions != null && key.longPressOptions.isNotEmpty()) {
                isLongPressed = true
                keyPopup.dismiss()
                activePopupOptionIndex = key.longPressDefaultIndex
                keyPopup.showLongPress(this, key.longPressOptions, activePopupOptionIndex, isDark, currentTheme, key.rect)
                invalidate()
            } else if (key.code == "SHIFT") {
                isLongPressed = true
                onKey?.invoke("SHIFT_LONG")
                invalidate()
            }
        }
    }

    init {
        setupKeysForMode()
    }

    private fun requestLayoutAndCalculate() {
        if (width > 0 && height > 0) {
            calculateKeyCoordinates(width, height)
        }
        invalidate()
    }

    private fun getSecondaryLabel(letter: String, isShifted: Boolean): String? {
        val label = secondaryKeyMap[letter] ?: return null
        return if (isShifted) label.uppercase() else label
    }

    private fun getLongPressOptions(letter: String, isShifted: Boolean): List<String>? {
        val list = longPressSymbolMap[letter] ?: secondaryKeyMap[letter]?.let { listOf(it) } ?: return null
        return if (isShifted) list.map { it.uppercase() } else list
    }

    private fun setupKeysForMode() {
        keys.clear()
        when (internalKeyboardMode) {
            "ABC" -> {
                val row0 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
                val row1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
                val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
                val row3 = listOf("SHIFT", "z", "x", "c", "v", "b", "n", "m", "BACKSPACE")

                row0.forEach { digit ->
                    keys.add(Key(
                        code = digit,
                        label = digit
                    ))
                }
                row1.forEach { letter ->
                    val options = getLongPressOptions(letter, shiftState > 0)
                    keys.add(Key(
                        code = letter,
                        label = letter,
                        secondaryLabel = getSecondaryLabel(letter, shiftState > 0),
                        longPressOptions = options,
                        longPressDefaultIndex = 0
                    ))
                }
                row2.forEach { letter ->
                    val options = getLongPressOptions(letter, shiftState > 0)
                    keys.add(Key(
                        code = letter,
                        label = letter,
                        secondaryLabel = getSecondaryLabel(letter, shiftState > 0),
                        longPressOptions = options,
                        longPressDefaultIndex = 0
                    ))
                }
                row3.forEach { letter ->
                    when (letter) {
                        "SHIFT" -> keys.add(Key(code = "SHIFT", label = "⇧", isFunctional = true, weight = 1.4f))
                        "BACKSPACE" -> keys.add(Key(code = "BACKSPACE", label = "⌫", isFunctional = true, weight = 1.4f))
                        else -> {
                            val options = getLongPressOptions(letter, shiftState > 0)
                            keys.add(Key(
                                code = letter,
                                label = letter,
                                secondaryLabel = getSecondaryLabel(letter, shiftState > 0),
                                longPressOptions = options,
                                longPressDefaultIndex = 0
                            ))
                        }
                    }
                }
                // Row 4
                keys.add(Key(code = "SYM", label = "?123", isFunctional = true, weight = 1.4f))
                keys.add(Key(
                    code = ",",
                    label = ",",
                    isFunctional = true,
                    weight = 1.2f
                ))
                keys.add(Key(code = "SPACE", label = "Space", weight = 5.5f))
                keys.add(Key(
                    code = ".",
                    label = ".",
                    isFunctional = true,
                    weight = 1.2f
                ))
                keys.add(Key(code = "ENTER", label = "Enter", isSpecialEnter = true, isFunctional = true, weight = 1.4f))
            }
            else -> { // "SYM1" or "SYM2"
                val isPage2 = internalKeyboardMode == "SYM2"
                val row0 = if (!isPage2) {
                    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
                } else {
                    listOf("€", "£", "¥", "₫", "¢", "₩", "₽", "¤", "π", "‰")
                }

                val row1 = if (!isPage2) {
                    listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/")
                } else {
                    listOf("<", ">", "[", "]", "{", "}", "“", "”", "‘", "’")
                }

                val row2 = if (!isPage2) {
                    listOf("*", "\"", "'", ":", ";", "!", "?", "\\", "~", "•")
                } else {
                    listOf("±", "−", "≠", "≈", "≤", "≥", "√", "∞", "«", "»")
                }

                val row3 = if (!isPage2) {
                    listOf("%", "=", "^", "×", "÷", "°", "…")
                } else {
                    listOf("§", "¶", "¡", "¿", "©", "®", "™")
                }

                row0.forEach { sym ->
                    keys.add(Key(
                        code = sym,
                        label = sym,
                        longPressOptions = symbolLongPressMap[sym]
                    ))
                }
                row1.forEach { sym ->
                    keys.add(Key(
                        code = sym,
                        label = sym,
                        longPressOptions = symbolLongPressMap[sym]
                    ))
                }
                row2.forEach { sym ->
                    keys.add(Key(
                        code = sym,
                        label = sym,
                        longPressOptions = symbolLongPressMap[sym]
                    ))
                }
                
                // Row 3 starts with switch page button
                val toggleLabel = if (!isPage2) "=\\<" else "?123"
                keys.add(Key(code = "SWITCH_PAGE", label = toggleLabel, isFunctional = true, weight = 1.4f))
                
                row3.forEach { sym ->
                    keys.add(Key(
                        code = sym,
                        label = sym,
                        longPressOptions = symbolLongPressMap[sym]
                    ))
                }
                
                // Backspace is the last key on row 3
                keys.add(Key(code = "BACKSPACE", label = "⌫", isFunctional = true, weight = 1.4f))

                // Row 4 (Bottom control row)
                keys.add(Key(code = "ABC", label = "ABC", isFunctional = true, weight = 1.4f))
                keys.add(Key(
                    code = ",",
                    label = ",",
                    isFunctional = true,
                    weight = 1.2f
                ))
                keys.add(Key(code = "SPACE", label = "Space", weight = 5.5f))
                keys.add(Key(
                    code = ".",
                    label = ".",
                    isFunctional = true,
                    weight = 1.2f
                ))
                keys.add(Key(code = "ENTER", label = "Enter", isSpecialEnter = true, isFunctional = true, weight = 1.4f))
            }
        }
        updateLabels()
    }

    private fun updateLabels() {
        keys.forEach { key ->
            if (key.code == "SPACE") {
                key.label = if (languageMode == "VIE") "‹   VI   ›" else "‹   EN   ›"
            } else if (key.code == "SHIFT") {
                key.label = when (shiftState) {
                    1 -> "⬆"
                    2 -> "⇪"
                    else -> "⇧"
                }
            } else if (key.code == "ENTER") {
                key.label = KeyboardUtils.getEnterTextLabel(imeOptions, inputType)
            } else if (key.code == "BACKSPACE" || key.code == "SYM" || key.code == "," || key.code == "SWITCH_PAGE" || key.code == "ABC" || key.code == "TPAD" || key.code == "SYM_PICKER") {
                // Keep static
            } else if (key.code.length == 1 && key.code[0] in '0'..'9') {
                key.label = key.code
            } else {
                key.label = if (shiftState > 0) key.code.uppercase() else key.code
            }
        }
    }

    private fun getRowsForMode(): List<List<Key>> {
        val rows = mutableListOf<List<Key>>()
        if (keys.isEmpty()) return rows

        if (internalKeyboardMode == "ABC") {
            // Row 0 (New number row): 10 keys (0..9)
            if (keys.size >= 10) rows.add(keys.subList(0, 10))
            // Row 1 (QWERTY row): 10 keys (10..19)
            if (keys.size >= 20) rows.add(keys.subList(10, 20))
            // Row 2 (ASDF row): 9 keys (20..28)
            if (keys.size >= 29) rows.add(keys.subList(20, 29))
            // Row 3 (ZXCV row): 9 keys (29..37)
            if (keys.size >= 38) rows.add(keys.subList(29, 38))
            // Row 4 (Space row): remaining keys
            if (keys.size > 38) rows.add(keys.subList(38, keys.size))
        } else {
            // Row 0: 10 keys
            if (keys.size >= 10) rows.add(keys.subList(0, 10))
            // Row 1: 10 keys
            if (keys.size >= 20) rows.add(keys.subList(10, 20))
            // Row 2: 10 keys
            if (keys.size >= 30) rows.add(keys.subList(20, 30))
            // Row 3: 9 keys (SWITCH_PAGE + row3 + BACKSPACE)
            if (keys.size >= 39) rows.add(keys.subList(30, 39))
            // Row 4: bottom row (remaining keys)
            if (keys.size > 39) rows.add(keys.subList(39, keys.size))
        }
        return rows
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateKeyCoordinates(w, h)
    }

    private fun calculateKeyCoordinates(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        val paddingLeft = 4 * density
        val paddingRight = 4 * density
        val paddingTop = 6 * density
        val paddingBottom = 4 * density

        val usableWidth = width - paddingLeft - paddingRight
        val usableHeight = height - paddingTop - paddingBottom

        val rows = getRowsForMode()
        val totalRowWeight = 5.0f
        val unitRowHeight = KeyboardUtils.calculateStandardRowHeight(height.toFloat(), density, 5, verticalSpacing)

        var currentY = paddingTop
        for (rowIndex in rows.indices) {
            val row = rows[rowIndex]
            val rowHeight = unitRowHeight
            val topOfRow = currentY
            val bottomOfRow = topOfRow + rowHeight

            if (internalKeyboardMode == "ABC" && rowIndex == 2) {
                val r2WidthAvailable = usableWidth - (horizontalSpacing * 10)
                val r2UnitWidth = r2WidthAvailable / 9.64f
                val r2SideMargin = 0.32f * r2UnitWidth
                var currentX = paddingLeft + r2SideMargin
                for (key in row) {
                    key.visualRect.set(
                        currentX,
                        topOfRow,
                        currentX + r2UnitWidth,
                        bottomOfRow
                    )
                    key.shadowRect.set(
                        key.visualRect.left,
                        key.visualRect.top + 0.8f * density,
                        key.visualRect.right,
                        key.visualRect.bottom + 1.2f * density
                    )
                    currentX += r2UnitWidth + horizontalSpacing
                }
            } else {
                val totalSpacings = row.size - 1
                val widthAvailable = usableWidth - (horizontalSpacing * totalSpacings)
                val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
                val unitWidth = widthAvailable / totalWeight

                var currentX = paddingLeft
                for (key in row) {
                    val actualWidth = key.weight * unitWidth
                    key.visualRect.set(
                        currentX,
                        topOfRow,
                        currentX + actualWidth,
                        bottomOfRow
                    )
                    key.shadowRect.set(
                        key.visualRect.left,
                        key.visualRect.top + 0.8f * density,
                        key.visualRect.right,
                        key.visualRect.bottom + 1.2f * density
                    )
                    currentX += actualWidth + horizontalSpacing
                }
            }
            currentY += rowHeight + verticalSpacing
        }

        for (rowIndex in rows.indices) {
            val row = rows[rowIndex]
            
            val topBound = if (rowIndex == 0) {
                0f
            } else {
                val prevRow = rows[rowIndex - 1]
                val prevBottom = prevRow[0].visualRect.bottom
                val currTop = row[0].visualRect.top
                (prevBottom + currTop) / 2f
            }
            
            val bottomBound = if (rowIndex == rows.size - 1) {
                height.toFloat()
            } else {
                val nextRow = rows[rowIndex + 1]
                val currBottom = row[0].visualRect.bottom
                val nextTop = nextRow[0].visualRect.top
                (currBottom + nextTop) / 2f
            }

            for (i in row.indices) {
                val key = row[i]
                
                val leftBound = if (i == 0) {
                    0f
                } else {
                    val prevRight = row[i - 1].visualRect.right
                    val currLeft = key.visualRect.left
                    (prevRight + currLeft) / 2f
                }
                
                val rightBound = if (i == row.size - 1) {
                    width.toFloat()
                } else {
                    val currRight = key.visualRect.right
                    val nextLeft = row[i + 1].visualRect.left
                    (currRight + nextLeft) / 2f
                }
                
                key.rect.set(leftBound, topBound, rightBound, bottomBound)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        keys.forEach { key ->
            drawKey(canvas, key)
        }
    }

    private fun drawKey(canvas: Canvas, key: Key) {
        val isPressed = key.isPressed
        val scale = if (isPressed) 0.96f else 1.0f

        val w = key.visualRect.width()
        val h = key.visualRect.height()
        val cx = key.visualRect.centerX()
        val cy = key.visualRect.centerY()

        drawRect.set(
            cx - w * scale / 2f,
            cy - h * scale / 2f,
            cx + w * scale / 2f,
            cy + h * scale / 2f
        )

        val bgColor = if (key.isFunctional || key.isSpecialEnter) {
            functionalKeyBgColor
        } else {
            keyBgColor
        }
        val pressedBgColor = if (key.isFunctional || key.isSpecialEnter) functionalKeyPressedBgColor else keyPressedBgColor

        KeyRenderer.drawStandardKey(
            canvas = canvas,
            drawRect = drawRect,
            shadowRect = key.shadowRect,
            cornerRadius = keyCornerRadius,
            density = density,
            isDark = isDark,
            keyStyle = keyStyle,
            isPressed = isPressed,
            isFunctional = key.isFunctional,
            isSpecialEnter = key.isSpecialEnter,
            bgColor = bgColor,
            pressedBgColor = pressedBgColor
        )

        textPaint.typeface = boldTypeface
        textPaint.color = textColor
        val isShiftActive = shiftState > 0
        
        val isSingleChar = key.label.length == 1
        val isEnter = key.code == "ENTER"
        
        if (isEnter) {
            textPaint.textSize = if (isSingleChar) 22f * density else 15f * density
        } else if (key.code == "SHIFT") {
            textPaint.textSize = 21f * density
        } else if (isSingleChar) {
            textPaint.textSize = 21f * density
        } else if (key.isFunctional) {
            textPaint.textSize = 13f * density
        } else {
            textPaint.textSize = 16f * density
        }

        if (key.code == "SHIFT") {
            val shiftColor = textColor
            KeyboardUtils.drawShiftIcon(canvas, drawRect, shiftState, density, shiftColor)
        } else if (isEnter) {
            val enterColor = textColor
            KeyboardUtils.drawEnterIcon(canvas, drawRect, imeOptions, inputType, density, enterColor)
        } else if (key.code == "SPACE") {
            val spaceText = if (languageMode == "VIE") "Tiếng Việt" else "English"
            textPaint.textSize = 12.5f * density
            textPaint.color = subTextColor
            textPaint.typeface = normalTypeface
            val baseline = drawRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(spaceText, drawRect.centerX(), baseline, textPaint)
            
            val indicatorW = 36f * density
            val indicatorH = 2.5f * density
            val indicatorY = drawRect.bottom - 7f * density
            val indicatorLeft = drawRect.centerX() - indicatorW / 2f
            shadowDrawRect.set(indicatorLeft, indicatorY - indicatorH, indicatorLeft + indicatorW, indicatorY)
            
            paint.color = activeAccentColor
            paint.alpha = if (isDark) 90 else 130
            canvas.drawRoundRect(shadowDrawRect, 1.2f * density, 1.2f * density, paint)
        } else {
            val baseline = drawRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(key.label, drawRect.centerX(), baseline, textPaint)
        }

        if (key.secondaryLabel != null && !isShiftActive && key.code != "SPACE") {
            textPaint.textSize = 9f * density
            textPaint.color = subTextColor
            val secX = drawRect.right - 5f * density
            val secY = drawRect.top + drawRect.height() * 0.28f
            val textWidth = textPaint.measureText(key.secondaryLabel)
            val secCenterX = secX - textWidth / 2f
            canvas.drawText(key.secondaryLabel, secCenterX, secY, textPaint)
        }
    }

    private fun handleKeyRelease(key: Key) {
        if (isLongPressed && key == activeTouchedKey) {
            if (key.longPressOptions != null && activePopupOptionIndex in key.longPressOptions.indices) {
                val selectedOption = key.longPressOptions[activePopupOptionIndex]
                onKey?.invoke(selectedOption)
            }
        } else if (isCursorSwipeActive && key.code == "SPACE") {
            // Sliding cursor handled, skip normal release dispatch
        } else if (key.code == "BACKSPACE" && backspaceSelectCount > 0) {
            // Sliding backspace delete handled, skip normal release dispatch
        } else {
            when (key.code) {
                "SHIFT" -> {
                    onKey?.invoke("SHIFT")
                }
                "BACKSPACE" -> {
                    if (!hasBackspaceTriggered && backspaceSelectCount == 0) {
                        onKey?.invoke("BACKSPACE")
                    }
                }
                "SYM" -> {
                    onSwitchToSymbols?.invoke()
                }
                "SWITCH_PAGE" -> {
                    internalKeyboardMode = if (internalKeyboardMode == "SYM1") "SYM2" else "SYM1"
                    setupKeysForMode()
                    requestLayoutAndCalculate()
                }
                "ABC" -> {
                    onKey?.invoke("ABC")
                }
                "ENTER" -> {
                    onKey?.invoke("ENTER")
                }
                else -> {
                    val textVal = if (key.code == "SPACE") {
                        "SPACE"
                    } else {
                        key.code
                    }
                    onKey?.invoke(textVal)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val px = event.getX(index)
                val py = event.getY(index)

                val key = findKeyByCoordinates(px, py)
                if (key != null) {
                    activePointerKeys[id] = key
                    key.isPressed = true

                    if (action == MotionEvent.ACTION_DOWN) {
                        startX = px
                        startY = py
                        isLongPressed = false
                        isCursorSwipeActive = false
                        isBackspaceSwipeActive = false
                        backspaceRepeatHandler.stop()
                        activeTouchedKey = key

                        if (key.code == "SPACE") {
                            cursorSwipeStartX = px
                            cursorLastTriggerX = px
                        }

                        if (key.code == "BACKSPACE") {
                            backspaceStartX = px
                            backspaceSelectCount = 0
                            isBackspaceSwipeActive = true
                            hasBackspaceTriggered = false
                            backspaceRepeatHandler.start()
                        }

                        // Show key preview popup
                        if (key.code != "SHIFT" && key.code != "BACKSPACE" && key.code != "ENTER" && key.code != "SPACE" && key.code != "SYM" && key.code != "ABC" && key.code != "SWITCH_PAGE") {
                            keyPopup.showPreview(this, key.label, isDark, currentTheme, key.rect)
                        }

                        longPressHandler.postDelayed(longPressRunnable, 350)
                    }

                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val px = event.getX(i)
                    val py = event.getY(i)

                    val trackedKey = activePointerKeys[id]
                    if (trackedKey != null) {
                        if (trackedKey == activeTouchedKey) {
                            val deltaX = Math.abs(px - startX)
                            val deltaY = Math.abs(py - startY)

                            if (isLongPressed) {
                                val options = trackedKey.longPressOptions
                                if (options != null && options.isNotEmpty()) {
                                    val defaultIdx = trackedKey.longPressDefaultIndex
                                    val step = 32 * density
                                    val dragOffset = px - startX
                                    val hoveredIdx = (defaultIdx + (dragOffset / step).toInt()).coerceIn(0, options.size - 1)
                                    if (hoveredIdx != activePopupOptionIndex) {
                                        activePopupOptionIndex = hoveredIdx
                                        keyPopup.updateHoverIndex(hoveredIdx)
                                    }
                                }
                            } else {
                                val currentHovered = findKeyByCoordinates(px, py)
                                if (currentHovered != null && currentHovered != trackedKey && !isCursorSwipeActive && !isBackspaceSwipeActive) {
                                    // Slide to a new key
                                    trackedKey.isPressed = false
                                    currentHovered.isPressed = true
                                    activePointerKeys[id] = currentHovered
                                    activeTouchedKey = currentHovered

                                    // Reset long-press delay timer for the newly hovered key
                                    longPressHandler.removeCallbacks(longPressRunnable)
                                    if (currentHovered.longPressOptions != null && currentHovered.longPressOptions.isNotEmpty()) {
                                        longPressHandler.postDelayed(longPressRunnable, 350)
                                    }

                                    // Update key preview dynamically
                                    val isPreviewable = currentHovered.code != "SHIFT" && 
                                                       currentHovered.code != "BACKSPACE" && 
                                                       currentHovered.code != "ENTER" && 
                                                       currentHovered.code != "SPACE" && 
                                                       currentHovered.code != "SYM" && 
                                                       currentHovered.code != "ABC" && 
                                                       currentHovered.code != "SWITCH_PAGE"
                                    if (isPreviewable) {
                                        keyPopup.showPreview(this, currentHovered.label, isDark, currentTheme, currentHovered.rect)
                                    } else {
                                        keyPopup.dismiss()
                                    }

                                    invalidate()
                                } else if (currentHovered == null) {
                                    // Dismiss preview if finger goes completely off any key bounds
                                    longPressHandler.removeCallbacks(longPressRunnable)
                                    keyPopup.dismiss()
                                    trackedKey.isPressed = false
                                    activeTouchedKey = null
                                    invalidate()
                                }

                                if (trackedKey.code == "SPACE") {
                                    val swipeDelta = px - cursorSwipeStartX
                                    if (Math.abs(swipeDelta) > 20f * density) {
                                        isCursorSwipeActive = true
                                    }

                                    if (isCursorSwipeActive) {
                                        val triggerStep = 10f * density
                                        val diffX = px - cursorLastTriggerX
                                        if (diffX > triggerStep) {
                                            onKey?.invoke("RIGHT_MOVE")
                                            cursorLastTriggerX = px
                                        } else if (diffX < -triggerStep) {
                                            onKey?.invoke("LEFT_MOVE")
                                            cursorLastTriggerX = px
                                        }
                                    }
                                }

                                if (trackedKey.code == "BACKSPACE" && isBackspaceSwipeActive) {
                                    val swipeDeltaX = px - backspaceStartX
                                    if (Math.abs(swipeDeltaX) > 10f * density) {
                                        backspaceRepeatHandler.stop()
                                    }

                                    if (swipeDeltaX < -30f * density) {
                                        val wordsToDelete = (-swipeDeltaX / (30f * density)).toInt()
                                        if (wordsToDelete > backspaceSelectCount) {
                                            val diff = wordsToDelete - backspaceSelectCount
                                            backspaceSelectCount = wordsToDelete
                                            repeat(diff) {
                                                onKey?.invoke("DELETE_WORD")
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            val currentHovered = findKeyByCoordinates(px, py)
                            if (currentHovered != trackedKey && currentHovered != null && !activePointerKeys.values.contains(currentHovered)) {
                                trackedKey.isPressed = false
                                currentHovered.isPressed = true
                                activePointerKeys[id] = currentHovered
                                invalidate()
                            }
                        }
                    } else {
                        val currentHovered = findKeyByCoordinates(px, py)
                        if (currentHovered != null && !activePointerKeys.values.contains(currentHovered)) {
                            activePointerKeys[id] = currentHovered
                            currentHovered.isPressed = true
                            invalidate()
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val trackedKey = activePointerKeys.remove(id)
                if (trackedKey != null) {
                    trackedKey.isPressed = false
                    if (trackedKey == activeTouchedKey) {
                        keyPopup.dismiss()
                    }
                    handleKeyRelease(trackedKey)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                backspaceRepeatHandler.stop()

                if (isLongPressed) {
                    val key = activeTouchedKey ?: activePointerKeys[event.getPointerId(0)]
                    if (key != null) {
                        if (key.longPressOptions != null && activePopupOptionIndex in key.longPressOptions.indices) {
                            val selectedOption = key.longPressOptions[activePopupOptionIndex]
                            onKey?.invoke(selectedOption)
                        } else {
                            val defaultIdx = key.longPressDefaultIndex
                            if (key.longPressOptions != null && defaultIdx in key.longPressOptions.indices) {
                                val selectedOption = key.longPressOptions[defaultIdx]
                                onKey?.invoke(selectedOption)
                            }
                        }
                    }
                    keyPopup.dismiss()

                    activePointerKeys.forEach { (_, k) -> k.isPressed = false }
                    activePointerKeys.clear()

                    activeTouchedKey = null
                    isLongPressed = false
                    isCursorSwipeActive = false
                    isBackspaceSwipeActive = false
                    invalidate()
                    return true
                }

                val id = event.getPointerId(0)
                val trackedKey = activePointerKeys.remove(id)
                
                keyPopup.dismiss()

                if (trackedKey != null) {
                    trackedKey.isPressed = false
                    handleKeyRelease(trackedKey)
                } else activeTouchedKey?.let { key ->
                    key.isPressed = false
                    handleKeyRelease(key)
                }

                activePointerKeys.forEach { (_, key) -> key.isPressed = false }
                activePointerKeys.clear()

                activeTouchedKey = null
                isLongPressed = false
                isCursorSwipeActive = false
                isBackspaceSwipeActive = false
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                backspaceRepeatHandler.stop()

                keyPopup.dismiss()

                activePointerKeys.forEach { (_, key) -> key.isPressed = false }
                activePointerKeys.clear()
                activeTouchedKey?.isPressed = false

                activeTouchedKey = null
                isLongPressed = false
                isCursorSwipeActive = false
                isBackspaceSwipeActive = false
                invalidate()
            }
        }
        return true
    }

    private fun findKeyByCoordinates(x: Float, y: Float): Key? {
        keys.forEach { key ->
            if (key.rect.contains(x, y)) {
                return key
            }
        }
        return null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        backspaceRepeatHandler.stop()
        longPressHandler.removeCallbacksAndMessages(null)
    }
}
