package com.goviet.keyboard.ui

/**
 * The bottom row of the full-width panels, described once.
 *
 * Emoji and symbols used to spell the same six keys out separately, with the
 * same weights and their own copy of the "split the width by weight, minus the
 * gaps" arithmetic. Every panel here draws its bottom row from this, so a
 * change lands on all of them and the two cannot quietly drift apart again.
 *
 * The only genuine difference between the two is the middle switch key: from
 * emoji you jump to symbols, from symbols you jump back to emoji.
 *
 * Tpad and the edit pad do not use it. A numeric pad has no comma key and no
 * separate backspace in its last row, and forcing it through this shape is how
 * a shared spec turns into a lowest-common-denominator one.
 */
data class BottomBarSpec(
    val switchKeyCode: String,
    val switchKeyLabel: String,
    val enterLabel: String
) {
    fun buildKeys(): List<Key> = listOf(
        Key(code = "ABC", label = "ABC", weight = ABC, isFunctional = true),
        Key(code = COMMA_CODE, label = ",", weight = COMMA),
        Key(code = switchKeyCode, label = switchKeyLabel, weight = SWITCH, isFunctional = true),
        // The space key is drawn from the language mode, not from its label.
        Key(code = "SPACE", label = "", weight = SPACE),
        Key(code = "BACKSPACE", label = BACKSPACE_GLYPH, weight = BACKSPACE, isFunctional = true),
        Key(code = "ENTER", label = enterLabel, weight = ENTER, isSpecialEnter = true)
    )

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

        /**
         * Rows the panel height is divided by when sizing the bottom row. Five,
         * not one: the row height is measured as if the letter keyboard's five
         * rows were above it, so the bar sits where it always has.
         */
        const val ROW_COUNT = 5

        // Weights sum to 9.3, so a 360dp panel gives roughly: ABC 46dp,
        // comma 35dp, switch 39dp, space 120dp, backspace 42dp, enter 46dp.
        const val ABC = 1.3f
        const val COMMA = 1.0f
        const val SWITCH = 1.1f
        const val SPACE = 3.4f
        const val BACKSPACE = 1.2f
        const val ENTER = 1.3f

        const val BACKSPACE_GLYPH = "⌫"

        /** Emoji panel: the middle key opens the symbol picker. */
        fun forEmoji(enterLabel: String) = BottomBarSpec(
            switchKeyCode = "!?#",
            switchKeyLabel = "!?#",
            enterLabel = enterLabel
        )

        /** Symbol picker: the middle key opens the emoji panel. */
        fun forSymbols(enterLabel: String) = BottomBarSpec(
            switchKeyCode = "EMOJI",
            switchKeyLabel = "🙂",
            enterLabel = enterLabel
        )
    }
}
