package com.goviet.keyboard.ui

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Shared geometry definitions and calculations for all picker panels (Emoji, Symbols).
 * Unifies column count, cell sizes, padding, clipping boundaries, and touch hit-testing.
 */
object PickerGridGeometry {
    const val COLS = 7
    const val DEFAULT_TOP_PADDING_DP = 2f
    const val DEFAULT_BOTTOM_PADDING_DP = 2f
    const val DEFAULT_CARD_PADDING_DP = 1.5f
    const val DEFAULT_CORNER_RADIUS_DP = 6f

    data class Layout(
        val cols: Int,
        val gridLeft: Float,
        val gridRight: Float,
        val gridTop: Float,
        val gridBottom: Float,
        val cellSize: Float,
        val rowCount: Int,
        val maxScrollOffset: Float
    ) {
        val gridWidth: Float get() = gridRight - gridLeft
        val gridHeight: Float get() = gridBottom - gridTop

        fun visibleIndices(scrollOffset: Float, itemCount: Int): IntRange {
            if (cellSize <= 0f || rowCount <= 0 || itemCount <= 0) return 0 until 0
            val firstRow = max(0, (scrollOffset / cellSize).toInt())
            val lastRow = min(rowCount - 1, ((gridHeight + scrollOffset) / cellSize).toInt())
            return (firstRow * cols) until min(itemCount, (lastRow + 1) * cols)
        }

        fun cellRect(index: Int, scrollOffset: Float, cardPadding: Float, outRect: RectF) {
            val col = index % cols
            val row = index / cols
            val cellLeft = gridLeft + col * cellSize
            val cellTop = gridTop + row * cellSize - scrollOffset
            outRect.set(
                cellLeft + cardPadding,
                cellTop + cardPadding,
                cellLeft + cellSize - cardPadding,
                cellTop + cellSize - cardPadding
            )
        }

        fun findItemIndexAt(
            x: Float,
            y: Float,
            scrollOffset: Float,
            itemCount: Int,
            minTouchPx: Float
        ): Int {
            if (itemCount <= 0 || cellSize <= 0f) return -1
            if (y < gridTop || y > gridBottom) return -1
            if (x < gridLeft || x > gridRight) return -1

            val col = KeyGeometry.nearestCellIndex(
                position = x - gridLeft,
                origin = 0f,
                cellSize = cellSize,
                cellCount = cols,
                minTouchPx = minTouchPx
            )
            val row = KeyGeometry.nearestCellIndex(
                position = y - gridTop + scrollOffset,
                origin = 0f,
                cellSize = cellSize,
                cellCount = rowCount,
                minTouchPx = minTouchPx
            )
            if (col < 0 || row < 0) return -1
            val index = row * cols + col
            return if (index in 0 until itemCount) index else -1
        }
    }

    fun calculate(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        itemCount: Int,
        bottomRowTopPx: Float,
        horizontalPaddingPx: Float = 0f,
        topPaddingPx: Float = DEFAULT_TOP_PADDING_DP * density,
        bottomPaddingPx: Float = DEFAULT_BOTTOM_PADDING_DP * density,
        cols: Int = COLS
    ): Layout {
        val gridLeft = horizontalPaddingPx
        val gridRight = widthPx.toFloat() - horizontalPaddingPx
        val gridWidth = max(0f, gridRight - gridLeft)
        val gridTop = topPaddingPx
        val gridBottom = max(gridTop, bottomRowTopPx - bottomPaddingPx)
        val cellSize = if (cols > 0) gridWidth / cols.toFloat() else 0f
        val rowCount = if (cols > 0) (itemCount + cols - 1) / cols else 0
        val gridHeight = gridBottom - gridTop
        val totalContentHeight = rowCount * cellSize
        val maxScrollOffset = max(0f, totalContentHeight - gridHeight)

        return Layout(
            cols = cols,
            gridLeft = gridLeft,
            gridRight = gridRight,
            gridTop = gridTop,
            gridBottom = gridBottom,
            cellSize = cellSize,
            rowCount = rowCount,
            maxScrollOffset = maxScrollOffset
        )
    }
}
