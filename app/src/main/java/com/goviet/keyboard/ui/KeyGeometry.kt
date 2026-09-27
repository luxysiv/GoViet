package com.goviet.keyboard.ui

/**
 * The numbers and the arithmetic every panel shares to place its keys.
 *
 * The rows used to be laid out by hand in each view, which is how the symbol
 * grid ended up hit-testing its bottom row against a hardcoded height that
 * nothing drew. Anything two views must agree on belongs here.
 */
object KeyGeometry {

    /**
     * Smallest touch target a key has to answer to.
     *
     * Most drawn rows are smaller than this and stay that way, so the target is
     * the drawn rect grown to this around its centre ([minTouchPx], handed to
     * [findKeyAt]) rather than a bigger key. [standardRowHeight] has the
     * arithmetic for why a phone panel cannot simply be taller.
     */
    const val MIN_TOUCH_DP = 48f

    /** Gap between keys in a weight-laid-out row, in dp. */
    const val ROW_SPACING_DP = 4.5f

    /** Side and bottom padding of a panel, in dp. */
    const val PANEL_PADDING_DP = 4f

    /** Padding above the first row inside [standardRowHeight], in dp. */
    const val ROW_PADDING_TOP_DP = 6f

    /** Padding below the last row inside [standardRowHeight], in dp. */
    const val ROW_PADDING_BOTTOM_DP = 4f

    fun minTouchPx(density: Float): Float = MIN_TOUCH_DP * density

    fun rowSpacingPx(density: Float): Float = ROW_SPACING_DP * density

    fun panelPaddingPx(density: Float): Float = PANEL_PADDING_DP * density

    /**
     * Height of one row when [totalHeightPx] is split [rowCount] ways with
     * [verticalSpacingPx] between rows.
     *
     * This is the arithmetic the shared bottom bar already used, unchanged.
     *
     * What it produces, at the panel heights the app actually uses, is worth
     * writing down rather than remembering: a 255dp portrait phone row comes
     * out at 43.4dp, a 280dp tablet at 48.4dp, and landscape lands between
     * 24.4 and 30.4dp. A tablet row is therefore past the target with nothing
     * to do, and the other two reach it by growing the touch target past the
     * drawn key; [KeyGeometryTest] pins all three numbers so a future change
     * cannot quietly pass as "already meeting it".
     *
     * Growing the drawn row on a phone is not a matter of tuning this function:
     * five rows of 48dp plus the current padding and gaps need 278dp, and the
     * portrait phone panel is 255dp.
     *
     * The letter grid, Tpad and the edit pad still lay out their own rows: they
     * have genuinely different shapes (staggered rows, a uniform 4x4, a centre
     * pad), and bending them to this one is later work, not a cleanup.
     */
    fun standardRowHeight(
        totalHeightPx: Float,
        density: Float,
        rowCount: Int,
        verticalSpacingPx: Float
    ): Float {
        if (totalHeightPx <= 0f || rowCount <= 0) return 0f
        val paddingTop = ROW_PADDING_TOP_DP * density
        val paddingBottom = ROW_PADDING_BOTTOM_DP * density
        val usableHeight = totalHeightPx - paddingTop - paddingBottom - (verticalSpacingPx * (rowCount - 1))
        return usableHeight / rowCount
    }

    /**
     * Index of the cell a touch at [position] lands in, along one axis of a
     * uniform grid: [cellCount] cells of [cellSize] starting at [origin].
     *
     * Same rule as a key's touch target — the cell grows to [minTouchPx]
     * around its centre, and where two grown cells overlap the one whose centre
     * is nearer takes it. Returns -1 when the point is past the first or last
     * cell by more than half a target, which is how a touch on the control row
     * below a grid stays out of the grid even when the cells are bigger than the
     * gap.
     */
    fun nearestCellIndex(
        position: Float,
        origin: Float,
        cellSize: Float,
        cellCount: Int,
        minTouchPx: Float
    ): Int {
        if (cellSize <= 0f || cellCount <= 0) return -1
        val relative = position - origin
        val grow = ((minTouchPx - cellSize) / 2f).coerceAtLeast(0f)
        val contentEnd = cellSize * cellCount
        if (relative < -grow || relative > contentEnd + grow) return -1

        val exact = (relative / cellSize).toInt()
        if (exact in 0 until cellCount) {
            val cellEnd = cellSize * (exact + 1)
            if (relative >= 0f && relative <= cellEnd) return exact
        }

        // In a gap, or past an edge of the grid: the nearest centre wins.
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (index in 0 until cellCount) {
            val centre = cellSize * (index + 0.5f)
            val distance = kotlin.math.abs(relative - centre)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    /**
     * Width of one weight unit in a row of [keyCount] keys separated by
     * [spacingPx]: the leftover width after padding and gaps, split by the sum
     * of the key weights.
     */
    fun unitWidthForWeights(
        totalWidthPx: Float,
        density: Float,
        keyCount: Int,
        weights: List<Float>,
        spacingPx: Float = rowSpacingPx(density),
        paddingPx: Float = panelPaddingPx(density)
    ): Float {
        if (keyCount <= 0) return 0f
        val available = totalWidthPx - 2f * paddingPx - (spacingPx * (keyCount - 1))
        val totalWeight = weights.sum()
        return if (totalWeight <= 0f) 0f else available / totalWeight
    }

    /**
     * Places [keys] left to right inside a row of [rowHeightPx] whose top edge
     * is [rowTopPx], using [unitWidthPx] as the width of one weight unit — from
     * [unitWidthForWeights], which is where the total width is accounted for.
     * Keys are separated by [spacingPx] and the row is inset by [paddingPx] on
     * both sides, so the same call serves drawing and hit-testing.
     */
    fun layOutWeightedRow(
        keys: List<Key>,
        rowTopPx: Float,
        rowHeightPx: Float,
        unitWidthPx: Float,
        spacingPx: Float,
        paddingPx: Float,
        density: Float
    ) {
        var x = paddingPx
        for (key in keys) {
            val keyWidth = key.weight * unitWidthPx
            key.rect.set(x, rowTopPx, x + keyWidth, rowTopPx + rowHeightPx)
            key.visualRect.set(key.rect)
            key.applyShadow(density)
            x += keyWidth + spacingPx
        }
    }
}
