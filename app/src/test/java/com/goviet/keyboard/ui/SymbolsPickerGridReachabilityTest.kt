package com.goviet.keyboard.ui

import android.content.Context
import android.view.View
import com.goviet.core.density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Every symbol in every tab has to be reachable, which is the bug this panel
 * shipped with.
 *
 * It drew 24 cells of a paged grid and never scrolled. The "1?#" tab holds 27
 * symbols, so the last three sat in a row laid out below the visible area, and
 * the hit test checked against a grid whose bottom edge was a hardcoded 66dp
 * from the bottom of the panel — a row that was drawn off the panel and
 * untouchable at the same time. The list became a scroll instead of a page, so
 * the invariant to protect is: for every symbol there is a scroll position at
 * which a touch on that symbol's cell resolves to that symbol.
 *
 * Stated as a property rather than a screenshot, so it holds whether the panel
 * is tall enough to fit the list or short enough that the tail must be scrolled
 * to. Panel sizes are given in dp and converted with the density the view sees,
 * so the test does not depend on Robolectric's default qualifier.
 */
@RunWith(RobolectricTestRunner::class)
class SymbolsPickerGridReachabilityTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun dp(value: Float): Int = (value * context.density).toInt()

    private fun grid(symbols: List<String>, widthDp: Float, heightDp: Float): SymbolsPickerGridView {
        val view = SymbolsPickerGridView(context)
        val widthPx = dp(widthDp)
        val heightPx = dp(heightDp)
        view.symbolsList = symbols
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, widthPx, heightPx)
        return view
    }

    /** Centre of the cell holding [index], in viewport coordinates. */
    private fun cellCentre(index: Int, view: SymbolsPickerGridView): Pair<Float, Float> {
        val row = index / view.cols
        val col = index % view.cols
        val x = col * view.cellSize + view.cellSize / 2f
        val y = view.gridTop + row * view.cellSize - view.scrollOffset + view.cellSize / 2f
        return x to y
    }

    private fun assertEverySymbolReachable(
        view: SymbolsPickerGridView,
        symbols: List<String>,
        label: String
    ) {
        assertTrue("$label: grid has no cell size", view.cellSize > 0f)
        assertEquals("$label: row count", (symbols.size + view.cols - 1) / view.cols, view.rowCount)

        symbols.indices.forEach { index ->
            // The scroll position that brings this cell to the top of the
            // viewport, clamped to what the list can actually scroll.
            val wanted = ((index / view.cols) * view.cellSize).coerceIn(0f, view.maxScrollOffset)
            view.scrollOffset = wanted
            val (x, y) = cellCentre(index, view)
            val hit = view.findSymbolIndexAt(x, y)
            assertEquals(
                "$label: symbol '${symbols[index]}' at index $index is unreachable " +
                    "(scrollOffset=$wanted y=$y gridTop=${view.gridTop} gridBottom=${view.gridBottom})",
                index,
                hit
            )
        }
    }

    @Test
    fun `every symbol on the 1-3 tab is reachable on a phone-sized panel`() {
        val symbols = PickerData.SYMBOLS_MAP[1] ?: error("tab 1 is missing")
        // The tab that was broken: 27 symbols over four rows of eight.
        assertEquals(27, symbols.size)
        val view = grid(symbols, widthDp = 360f, heightDp = 255f)
        assertEverySymbolReachable(view, symbols, "1?# / 360x255dp")
    }

    @Test
    fun `every symbol is reachable on a panel too short to fit the list`() {
        val symbols = PickerData.SYMBOLS_MAP[1] ?: error("tab 1 is missing")
        // Landscape tablet: the list has to scroll here, so the tail symbols are
        // only reachable if the scroll range and the hit test agree.
        val view = grid(symbols, widthDp = 1024f, heightDp = 160f)
        assertTrue("this panel is meant to need scrolling", view.maxScrollOffset > 0f)
        assertEverySymbolReachable(view, symbols, "1?# / 1024x160dp")
    }

    @Test
    fun `every tab in the picker is reachable`() {
        PickerData.SYMBOLS_MAP.forEach { (tab, symbols) ->
            if (symbols.isEmpty()) return@forEach
            val view = grid(symbols, widthDp = 360f, heightDp = 255f)
            assertEverySymbolReachable(view, symbols, "tab $tab (${symbols.size} symbols)")
        }
    }

    @Test
    fun `switching tabs updates the row count without a re-layout`() {
        val view = grid(PickerData.SYMBOLS_MAP[1] ?: emptyList(), 360f, 255f)
        val first = view.rowCount

        // The bug's other half: the row count came from the previous list, so a
        // longer tab scrolled short and a shorter one could scroll past its end.
        val longest = PickerData.SYMBOLS_MAP.maxByOrNull { it.value.size }?.value ?: emptyList()
        view.symbolsList = longest
        assertEquals((longest.size + view.cols - 1) / view.cols, view.rowCount)
        assertTrue("a longer tab should not lose rows", view.rowCount >= first)

        val shortest = PickerData.SYMBOLS_MAP.minByOrNull { it.value.size }?.value ?: emptyList()
        view.symbolsList = shortest
        assertEquals((shortest.size + view.cols - 1) / view.cols, view.rowCount)
        assertTrue("scroll range is never negative", view.maxScrollOffset >= 0f)
    }

    @Test
    fun `the grid stops above the bottom bar, and the shared row height is why`() {
        val view = grid(PickerData.SYMBOLS_MAP[1] ?: emptyList(), 360f, 255f)
        val density = context.density

        val rowHeight = KeyGeometry.standardRowHeight(
            totalHeightPx = view.height.toFloat(),
            density = density,
            rowCount = BottomBarSpec.ROW_COUNT,
            verticalSpacingPx = 7f * density
        )
        val bottomRowTop = view.height - KeyGeometry.panelPaddingPx(density) - rowHeight

        // The old code used a fixed 66dp here, which lined up on some panel
        // heights and cut the grid off on others.
        assertEquals(2f * density, view.gridTop, 0.01f)
        assertEquals(bottomRowTop - 2f * density, view.gridBottom, 0.01f)
        assertTrue("grid has no height", view.gridBottom > view.gridTop)
    }

    @Test
    fun `an empty list has nothing to hit`() {
        val view = grid(emptyList(), 360f, 255f)
        assertEquals(0, view.rowCount)
        assertEquals(0f, view.maxScrollOffset, 0.01f)
        assertEquals(-1, view.findSymbolIndexAt(view.cellSize / 2f, view.gridTop + 1f))
    }

    @Test
    fun `a cell past the last symbol reports nothing`() {
        val symbols = PickerData.SYMBOLS_MAP[1] ?: error("tab 1 is missing")
        val view = grid(symbols, 360f, 255f)
        // 27 symbols over 7 columns: three full rows and six in the last, so
        // anything past the end is a tap on the empty seventh cell of the last
        // row. It must not fall through to a neighbour the way an unchecked
        // column index did.
        val emptyCell = symbols.size
        val row = emptyCell / view.cols
        val col = emptyCell % view.cols
        assertTrue("this test needs a partly filled last row", row < view.rowCount)

        view.scrollOffset = (row * view.cellSize).coerceIn(0f, view.maxScrollOffset)
        val (x, y) = cellCentre(emptyCell, view)
        assertEquals(-1, view.findSymbolIndexAt(x, y))
    }

    @Test
    fun `a touch outside the grid reports nothing`() {
        val symbols = PickerData.SYMBOLS_MAP[1] ?: error("tab 1 is missing")
        val view = grid(symbols, 360f, 255f)
        assertEquals(-1, view.findSymbolIndexAt(view.cellSize / 2f, view.gridTop - 1f))
        assertEquals(-1, view.findSymbolIndexAt(view.cellSize / 2f, view.gridBottom + 1f))
    }
}
