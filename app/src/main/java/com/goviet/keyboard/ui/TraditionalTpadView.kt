package com.goviet.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import com.goviet.R
import com.goviet.core.density
import com.goviet.keyboard.EditorFieldIntent

class TraditionalTpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyGridView(context, attrs, defStyleAttr) {

    // Properties
    var currentEditorInputType: Int = 0
        set(value) {
            field = value
            setupKeys()
        }

    var isOtpField: Boolean = false
        set(value) {
            field = value
            setupKeys()
        }

    var currentImeOptions: Int = 0
        set(value) {
            field = value
            setupKeys()
        }

    var currentInputType: Int = 0
        set(value) {
            field = value
            setupKeys()
        }

    // Callbacks
    var onKey: ((String) -> Unit)? = null
    var onSwitchToABC: (() -> Unit)? = null
    var onSwitchToSymbols: (() -> Unit)? = null

    private val keysList = mutableListOf<Key>()

    // Popup window for long press options
    private val keyPopup = KeyPopupWindow(context)
    private var activePopupOptionIndex = -1
    private var isLongPressed = false

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var activeTouchedKey: Key? = null

    // Reusable buffer for converting view-local coordinates to window coordinates.
    private val locationBuf = IntArray(2)

    // BACKSPACE repeating + sliding deletion (shared math, see BackspaceSwipeTracker)
    private val backspaceSwipe = BackspaceSwipeTracker()
    private val backspaceRepeatHandler = RepeatingKeyPressHandler(
        intervalProvider = { count: Int ->
            when {
                count < 5 -> 150L
                count < 10 -> 100L
                count < 15 -> 60L
                else -> 40L
            }
        }
    ) {
        onKey?.invoke("BACKSPACE")
    }

    private val longPressRunnable = Runnable {
        activeTouchedKey?.let { key ->
            val opts = key.longPressOptions
            if (opts != null && opts.isNotEmpty()) {
                isLongPressed = true
                activePopupOptionIndex = key.longPressDefaultIndex
                keyPopup.showLongPress(this, opts, activePopupOptionIndex, isDark, currentTheme, key.rect)
                invalidate()
            }
        }
    }

    /**
     * The keys as nodes for a screen reader, which otherwise sees one empty
     * rectangle for the whole pad and cannot reach a single digit.
     */
    private val accessibility = KeyGridTouchHelper(this) { accessibilityCells() }

    init {
        setupKeys()
        accessibility.attach()
    }

    /**
     * The pad's keys, in the order they are drawn, each with the label it
     * shows and the action it does.
     *
     * The description is what the key says, read out: a digit says its letters
     * too, because "2 abc" is the key, and the backspace says what it is
     * rather than what it is drawn as — a screen reader reading out a
     * backwards arrow says "backspace" in some voices and nothing at all in
     * others.
     */
    internal fun accessibilityCells(): List<KeyGridCell> {
        if (width <= 0 || height <= 0) return emptyList()
        return keysList.map { key ->
            KeyGridCell(
                bounds = RectF(key.visualRect),
                description = KeyboardUtils.keyNodeDescription(context, key, currentImeOptions, currentInputType),
                // The same call a touch makes, so a node and a finger cannot
                // drift apart: ABC switches panels, everything else types.
                onActivate = { handleKeyClick(key) }
            )
        }
    }

    /**
     * Forwards a screen reader's hover to the keys.
     *
     * Without this the pad is one node to a screen reader and the keys are
     * something it has to guess the position of by touch.
     */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateLayout()
    }

    /**
     * Places the four rows from [KeyGeometry], the way every other panel places
     * its keys, instead of dividing the panel by hand.
     *
     * The hand arithmetic is what had the T-pad's bottom row somewhere else:
     * 4dp of padding top and bottom and keys drawn 4dp inside a cell that
     * spanned the gap, so the last row ended 4dp higher than every other
     * panel's bottom row and its keys were 8dp apart rather than sharing the
     * row spacing. Same padding, same row height, same gap, same bottom edge
     * as the letter and emoji panels now; the keys are drawn at their cell, so
     * what is drawn is what the touch target already was.
     */
    private fun calculateLayout() {
        if (width <= 0 || height <= 0 || keysList.isEmpty()) return

        val padding = KeyGeometry.panelPaddingPx(density)
        val spacing = KeyGeometry.rowSpacingPx(density)
        val rowCount = keysList.size / COLUMN_COUNT
        if (rowCount <= 0) return

        val rowHeight = KeyGeometry.standardRowHeight(
            totalHeightPx = height.toFloat(),
            density = density,
            rowCount = rowCount,
            verticalSpacingPx = spacing
        )
        val cellWidth =
            (width - 2f * padding - spacing * (COLUMN_COUNT - 1)) / COLUMN_COUNT
        val firstRowTop = KeyGeometry.ROW_PADDING_TOP_DP * density

        for (index in keysList.indices) {
            val key = keysList[index]
            val row = index / COLUMN_COUNT
            val col = index % COLUMN_COUNT

            val cellLeft = padding + col * (cellWidth + spacing)
            val cellTop = firstRowTop + row * (rowHeight + spacing)

            key.rect.set(cellLeft, cellTop, cellLeft + cellWidth, cellTop + rowHeight)
            key.visualRect.set(key.rect)
            key.applyShadow(density)
        }
        // The keys are in new places, and a node that still describes the old
        // rectangle is a node a screen reader will send a finger to the wrong
        // key from.
        accessibility.invalidateRoot()
    }

    /**
     * Whether a drag along BACKSPACE may delete a whole word.
     *
     * No, in a field that is asking for numbers or calling itself a one-time
     * code. The swipe is a text gesture: dragging it across a card number, a
     * phone number or a code takes the whole number away, which is not what
     * dragging a finger over a number was ever going to mean to the person
     * doing it. The T-pad is still reachable from the letter keyboard by hand,
     * which is where the gesture stays available.
     */
    internal val allowsWordDelete: Boolean
        get() = !isOtpField && !EditorFieldIntent.isNumericInput(currentEditorInputType)

    /** The keys as they are currently laid out, for the geometry test. */
    internal fun laidOutKeys(): List<Key> = keysList

    private fun setupKeys() {
        keysList.clear()

        val classType = currentEditorInputType and 0x0000000f // EditorInfo.TYPE_MASK_CLASS
        val isPhone = classType == 3 // EditorInfo.TYPE_CLASS_PHONE
        val isNumber = classType == 2 // EditorInfo.TYPE_CLASS_NUMBER
        val isDatetime = classType == 4 // EditorInfo.TYPE_CLASS_DATETIME

        val isSigned = (currentEditorInputType and 0x00001000) != 0
        val isDecimal = (currentEditorInputType and 0x00002000) != 0

        // Row 1
        // 1 is the one digit that types nothing but itself, so long press is
        // the only way to anything else on it. Six, because the long-press popup
        // is 44dp per option and stops fitting a 360dp phone at eight; these
        // are the six no other key on the pad carries ("-", "+", "*", "#" and
        // "." are long presses of their own keys).
        keysList.add(
            Key(
                code = "1",
                label = "1",
                longPressOptions = listOf("=", "\"", "%", "&", "@", "_")
            )
        )
        keysList.add(Key(code = "2", label = "2"))
        keysList.add(Key(code = "3", label = "3"))
        keysList.add(Key(code = "BACKSPACE", label = "⌫", isFunctional = true))

        // Row 2
        keysList.add(Key(code = "4", label = "4"))
        keysList.add(Key(code = "5", label = "5"))
        keysList.add(Key(code = "6", label = "6"))
        if (isPhone) {
            keysList.add(Key(code = "*", label = "*", secondaryLabel = "#", longPressOptions = listOf("*", "#")))
        } else if (isDatetime) {
            keysList.add(Key(code = "-", label = "-", secondaryLabel = "/", longPressOptions = listOf("-", "/")))
        } else {
            keysList.add(Key(code = ".", label = ".", secondaryLabel = "-", longPressOptions = listOf(".", "-", "?", "!", ";", ":")))
        }

        // Row 3
        keysList.add(Key(code = "7", label = "7"))
        keysList.add(Key(code = "8", label = "8"))
        keysList.add(Key(code = "9", label = "9"))
        if (isPhone) {
            keysList.add(Key(code = "(", label = "(", secondaryLabel = ")", longPressOptions = listOf("(", ")")))
        } else if (isDatetime) {
            keysList.add(Key(code = ":", label = ":", secondaryLabel = "-", longPressOptions = listOf(":", "-")))
        } else if (isNumber && isSigned) {
            keysList.add(Key(code = "-", label = "-", secondaryLabel = "+", longPressOptions = listOf("-", "+")))
        } else {
            keysList.add(Key(code = ",", label = ",", secondaryLabel = "+", longPressOptions = listOf(",", "+", "-", "*", "/", "=")))
        }

        // Row 4
        keysList.add(Key(code = "ABC", label = "ABC", isFunctional = true))
        
        if (isPhone) {
            keysList.add(Key(code = "0", label = "0", secondaryLabel = "+", longPressOptions = listOf("0", "+")))
        } else {
            keysList.add(Key(code = "0", label = "0"))
        }

        if (isOtpField) {
            keysList.add(Key(code = "PASTE_OTP", label = context.getString(R.string.tpad_paste), isFunctional = true))
        } else if (isPhone) {
            keysList.add(Key(code = "-", label = "-"))
        } else if (isDatetime) {
            keysList.add(Key(code = "/", label = "/"))
        } else if (isNumber && isDecimal) {
            keysList.add(Key(code = ".", label = "."))
        } else {
            keysList.add(Key(code = "SPACE", label = context.getString(R.string.key_space)))
        }

        val enterLabel = KeyboardUtils.getEnterTextLabel(currentImeOptions, currentInputType)
        keysList.add(Key(code = "ENTER", label = enterLabel, isSpecialEnter = true))

        calculateLayout()
    }

    private fun handleKeyClick(key: Key) {
        if (key.code == "ABC") {
            onSwitchToABC?.invoke()
        } else if (key.code == "SYMBOLS") {
            onSwitchToSymbols?.invoke()
        } else {
            onKey?.invoke(key.code)
        }
    }


    private fun findKeyByCoordinates(x: Float, y: Float): Key? =
        findKeyAt(keysList, x, y, KeyGeometry.minTouchPx(density))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(panelBgColor)

        for (key in keysList) {
            computeScaledRect(
                cx = key.visualRect.centerX(),
                cy = key.visualRect.centerY(),
                w = key.visualRect.width(),
                h = key.visualRect.height(),
                scale = if (key.isPressed) 0.96f else 1.0f
            )
            drawKeyBackgroundScaled(canvas, key, cornerRadius = 8f * density)

            // Draw label. The space key is the one key that shows a picture of
            // itself: the word on it is for a screen reader, not for the eye.
            if (key.code == "SPACE") {
                KeyboardUtils.drawSpaceBar(canvas, drawRect, textPaint, subTextColor, density)
            } else {
                KeyboardUtils.drawKeyLabel(canvas, key.label, drawRect, textPaint, textColor, density, key.isFunctional)
            }

            val tpadSec = key.secondaryLabel
            if (tpadSec != null) {
                KeyboardUtils.drawSecondaryLabel(canvas, tpadSec, drawRect, textPaint, subTextColor, density)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val x = event.x
        val y = event.y

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val key = findKeyByCoordinates(x, y)
                if (key != null) {
                    activeTouchedKey = key
                    key.isPressed = true
                    isLongPressed = false
                    if (key.code == "BACKSPACE") {
                        backspaceSwipe.reset(x)
                        onKey?.invoke("BACKSPACE")
                        backspaceRepeatHandler.start()
                    } else {
                        longPressHandler.postDelayed(longPressRunnable, RepeatingKeyPressHandler.DEFAULT_INITIAL_DELAY_MS)
                    }
                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val trackedKey = activeTouchedKey
                if (trackedKey != null) {
                    if (isLongPressed) {
                        val options = trackedKey.longPressOptions
                        if (options != null && options.isNotEmpty()) {
                            getLocationInWindow(locationBuf)
                            val screenX = locationBuf[0] + x
                            activePopupOptionIndex = keyPopup.trackHoverForScreenX(
                                screenX, trackedKey.longPressDefaultIndex, activePopupOptionIndex
                            )
                        }
                    } else {
                        val currentHovered = findKeyByCoordinates(x, y)
                        if (currentHovered != trackedKey) {
                            trackedKey.isPressed = false
                            longPressHandler.removeCallbacks(longPressRunnable)
                            backspaceRepeatHandler.stop()

                            if (currentHovered != null) {
                                activeTouchedKey = currentHovered
                                currentHovered.isPressed = true
                                
                                if (currentHovered.code == "BACKSPACE") {
                                    backspaceSwipe.reset(x)
                                    onKey?.invoke("BACKSPACE")
                                    backspaceRepeatHandler.start()
                                } else {
                                    longPressHandler.postDelayed(longPressRunnable, RepeatingKeyPressHandler.DEFAULT_INITIAL_DELAY_MS)
                                }
                            } else {
                                activeTouchedKey = null
                            }
                            invalidate()
                        } else if (trackedKey.code == "BACKSPACE" && !isLongPressed) {
                            if (backspaceSwipe.shouldStopRepeat(x, density)) {
                                backspaceRepeatHandler.stop()
                            }
                            // Repeat still runs; only the word-sized jump is
                            // withheld, and only in a field that asked for
                            // digits (see allowsWordDelete).
                            val words = if (allowsWordDelete) backspaceSwipe.advanceWords(x, density) else 0
                            repeat(words) {
                                onKey?.invoke("DELETE_WORD")
                            }
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                backspaceRepeatHandler.stop()

                val trackedKey = activeTouchedKey
                if (trackedKey != null) {
                    trackedKey.isPressed = false
                    if (isLongPressed) {
                        val lpOpts = trackedKey.longPressOptions
                        if (lpOpts != null && activePopupOptionIndex in lpOpts.indices) {
                            val selectedOption = lpOpts[activePopupOptionIndex]
                            onKey?.invoke(selectedOption)
                        }
                        keyPopup.dismiss()
                    } else {
                        if (trackedKey.code == "BACKSPACE") {
                            // Already handled in down/repeat/swipe
                        } else {
                            handleKeyClick(trackedKey)
                        }
                    }
                }
                activeTouchedKey = null
                isLongPressed = false
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                backspaceRepeatHandler.stop()
                keyPopup.dismiss()
                activeTouchedKey?.let { it.isPressed = false }
                activeTouchedKey = null
                isLongPressed = false
                invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        backspaceRepeatHandler.stop()
        longPressHandler.removeCallbacksAndMessages(null)
    }

    private companion object {
        /** The pad is four keys wide, and four rows deep. */
        const val COLUMN_COUNT = 4
    }
}
