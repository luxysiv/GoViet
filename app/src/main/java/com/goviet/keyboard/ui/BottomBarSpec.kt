package com.goviet.keyboard.ui

/**
 * The bottom row of the full-width panels.
 *
 * Emoji and symbols share a 5-key bottom bar layout matching QWERTY and Symbol keyboards:
 * [ABC] [Switch: ?123 or 🙂] [Space / Language] [⌫] [↵]
 *
 * Space bar has weight 5.5f (out of 10.7f), exactly matching QWERTY and Symbol layouts.
 */
data class BottomBarSpec(
    val switchKeyCode: String,
    val switchKeyLabel: String,
    val enterLabel: String,
    val hasComma: Boolean = false
) {
    fun buildKeys(): List<Key> = if (hasComma) {
        listOf(
            Key(code = "ABC", label = "ABC", weight = ABC, isFunctional = true),
            Key(code = COMMA_CODE, label = ",", weight = COMMA),
            Key(code = switchKeyCode, label = switchKeyLabel, weight = SWITCH, isFunctional = true),
            Key(code = "SPACE", label = "", weight = SPACE),
            Key(code = "BACKSPACE", label = BACKSPACE_GLYPH, weight = BACKSPACE, isFunctional = true),
            Key(code = "ENTER", label = enterLabel, weight = ENTER, isSpecialEnter = true)
        )
    } else {
        listOf(
            Key(code = "ABC", label = "ABC", weight = ABC_5KEY, isFunctional = true),
            Key(code = switchKeyCode, label = switchKeyLabel, weight = SWITCH_5KEY, isFunctional = true),
            Key(code = "SPACE", label = "", weight = SPACE_5KEY),
            Key(code = "BACKSPACE", label = BACKSPACE_GLYPH, weight = BACKSPACE_5KEY, isFunctional = true),
            Key(code = "ENTER", label = enterLabel, weight = ENTER_5KEY, isSpecialEnter = true)
        )
    }

    /**
     * Lays [keys] (from [buildKeys]) into the bottom of a panel [widthPx] wide
     * and [heightPx] tall, and returns the top edge of the row.
     */
    fun layOut(
        keys: List<Key>,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        rowCount: Int,
        verticalSpacingPx: Float
    ): Float {
        if (widthPx <= 0 || heightPx <= 0) return heightPx.toFloat()
        val rowHeight = KeyGeometry.standardRowHeight(
            totalHeightPx = heightPx.toFloat(),
            density = density,
            rowCount = rowCount,
            verticalSpacingPx = verticalSpacingPx
        )
        val rowTop = heightPx - KeyGeometry.panelPaddingPx(density) - rowHeight
        val unitWidth = KeyGeometry.unitWidthForWeights(
            totalWidthPx = widthPx.toFloat(),
            density = density,
            keyCount = keys.size,
            weights = keys.map { it.weight }
        )
        KeyGeometry.layOutWeightedRow(
            keys = keys,
            rowTopPx = rowTop,
            rowHeightPx = rowHeight,
            unitWidthPx = unitWidth,
            spacingPx = KeyGeometry.rowSpacingPx(density),
            paddingPx = KeyGeometry.panelPaddingPx(density),
            density = density
        )
        return rowTop
    }

    companion object {
        const val COMMA_CODE = ","

        const val ROW_COUNT = 5

        // Legacy 6-key weights
        const val ABC = 1.3f
        const val COMMA = 1.0f
        const val SWITCH = 1.1f
        const val SPACE = 3.4f
        const val BACKSPACE = 1.2f
        const val ENTER = 1.3f

        // 5-key uniform weights matching QWERTY and Symbol keyboard bottom row
        // (1.4f + 1.2f + 5.5f + 1.2f + 1.4f = 10.7f)
        const val ABC_5KEY = 1.4f
        const val SWITCH_5KEY = 1.2f
        const val SPACE_5KEY = 5.5f
        const val BACKSPACE_5KEY = 1.2f
        const val ENTER_5KEY = 1.4f

        const val BACKSPACE_GLYPH = "⌫"

        /** Emoji panel: [ABC] [?123] [Space] [⌫] [↵] — comma removed, ?123 switch, 5.5f space bar */
        fun forEmoji(enterLabel: String) = BottomBarSpec(
            switchKeyCode = "SYM",
            switchKeyLabel = "?123",
            enterLabel = enterLabel,
            hasComma = false
        )

        /** Symbol picker: [ABC] [ , ] [🙂] [Space] [⌫] [↵] */
        fun forSymbols(enterLabel: String) = BottomBarSpec(
            switchKeyCode = "EMOJI",
            switchKeyLabel = "🙂",
            enterLabel = enterLabel,
            hasComma = true
        )
    }
}
