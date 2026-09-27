package com.goviet.keyboard.ui

import android.content.Context
import android.graphics.RectF
import android.view.View
import com.goviet.R
import com.goviet.core.density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The keys a screen reader is given, on the three panels that draw themselves.
 *
 * Every panel here is one rectangle to a screen reader — a View with a Canvas
 * in onDraw and no child views — so the emoji, the symbols and the numeric pad
 * were unreachable without guessing where a key was by touch. The fix is one
 * node per key, labelled with what the key shows and activating what the key
 * does, and these are the properties worth keeping: a node exists for every key
 * on screen, it says what the key says, it does what the key does, and its
 * rectangle is the key's own rectangle rather than the grown touch target,
 * which would put two overlapping frames on screen.
 */
@RunWith(RobolectricTestRunner::class)
class KeyGridAccessibilityTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun <T : View> layOut(view: T, widthDp: Float = 360f, heightDp: Float = 255f): T {
        val widthPx = (widthDp * context.density).toInt()
        val heightPx = (heightDp * context.density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, widthPx, heightPx)
        return view
    }

    private fun pad(): TraditionalTpadView = layOut(TraditionalTpadView(context))

    private fun symbols(list: List<String>): SymbolsPickerGridView =
        layOut(SymbolsPickerGridView(context).apply { symbolsList = list })

    private fun emoji(list: List<String>): TraditionalEmojiView =
        layOut(TraditionalEmojiView(context).apply { emojisList = list })

    private fun cellLabelled(cells: List<KeyGridCell>, label: String): KeyGridCell =
        cells.firstOrNull { it.description.toString() == label }
            ?: error("no node labelled '$label' in ${cells.map { it.description }}")

    @Test
    fun `every key of the pad is a node, labelled as the key is`() {
        val cells = pad().accessibilityCells()
        // Sixteen keys: a screen reader that cannot reach one of them is back
        // to guessing where it is.
        assertEquals(16, cells.size)

        val labels = cells.map { it.description.toString() }
        assertTrue("a digit should say the digit: $labels", "2" in labels)
        assertTrue("a digit should say the digit: $labels", "7" in labels)
        assertTrue("9" in labels)
        // Nothing is printed under a digit, so nothing is read out after it
        // either: "2 abc" describes a key that is not on the pad.
        assertFalse(
            "a node should not read out letters the key does not show: $labels",
            labels.any { it.length > 1 && it.first().isDigit() }
        )
        // The backspace is a word, not an arrow: a reader given a backwards
        // arrow either says "backspace" or says nothing at all.
        assertTrue(context.getString(R.string.tpad_backspace) in labels)
        assertFalse("the arrow glyph is not a label", "⌫" in labels)
    }

    @Test
    fun `a node on the pad is framed on the key, not on the grown target`() {
        val view = pad()
        val cells = view.accessibilityCells()
        val keys = view.laidOutKeys()
        assertEquals(keys.size, cells.size)
        cells.forEachIndexed { index, cell ->
            val key = keys[index]
            assertEquals(key.visualRect.left, cell.bounds.left, 0.01f)
            assertEquals(key.visualRect.top, cell.bounds.top, 0.01f)
            assertEquals(key.visualRect.right, cell.bounds.right, 0.01f)
            assertEquals(key.visualRect.bottom, cell.bounds.bottom, 0.01f)
        }
    }

    @Test
    fun `activating a digit node types the digit`() {
        val view = pad()
        val pressed = mutableListOf<String>()
        view.onKey = { pressed.add(it) }

        cellLabelled(view.accessibilityCells(), "7").onActivate()

        assertEquals(listOf("7"), pressed)
    }

    @Test
    fun `activating the backspace node deletes, and the ABC node switches`() {
        val view = pad()
        val pressed = mutableListOf<String>()
        var switched = 0
        view.onKey = { pressed.add(it) }
        view.onSwitchToABC = { switched++ }
        val cells = view.accessibilityCells()

        cellLabelled(cells, context.getString(R.string.tpad_backspace)).onActivate()
        cellLabelled(cells, "ABC").onActivate()

        assertEquals(listOf("BACKSPACE"), pressed)
        assertEquals(1, switched)
    }

    @Test
    fun `the space key is a node with a label, not a blank one`() {
        // The space key is drawn from the language mode rather than from a
        // label, which is exactly the key a screen reader used to find nothing
        // on.
        val view = pad()
        val space = cellLabelled(view.accessibilityCells(), context.getString(R.string.key_space))
        assertFalse("a blank node reads out nothing", space.description.isBlank())
    }

    @Test
    fun `every symbol on screen is a node that types its symbol`() {
        val list = listOf("!", "?", "#", "\$", "%", "&", "@")
        val view = symbols(list)
        var typed: String? = null
        view.onKey = { typed = it }

        val cells = view.accessibilityCells()
        val symbolNodes = cells.filter { cell -> list.contains(cell.description.toString()) }
        assertEquals("a node for every symbol the panel shows", list.size, symbolNodes.size)

        val index = view.findSymbolIndexAt(view.cellSize / 2f, view.gridTop + view.cellSize / 2f)
        assertTrue("the test needs to point at a real symbol", index >= 0)
        cellLabelled(cells, list[index]).onActivate()
        assertEquals(list[index], typed)
    }

    @Test
    fun `the control row is reachable too, so backspace is not lost with the cells`() {
        val view = symbols(listOf("!", "?"))
        val pressed = mutableListOf<String>()
        view.onKey = { pressed.add(it) }

        val backspace = cellLabelled(view.accessibilityCells(), context.getString(R.string.tpad_backspace))
        backspace.onActivate()

        assertEquals(listOf("BACKSPACE"), pressed)
    }

    @Test
    fun `the control row of a scrolling panel says what its keys are`() {
        // The space key is drawn from the language mode and carries no label,
        // and the backspace is drawn as an arrow: a node that took the label as
        // it stands would either be blank or read out a glyph.
        val symbolsView = symbols(listOf("!", "?"))
        val emojiView = emoji(listOf("😀", "😁", "😂", "😄", "😅", "😆", "😅", "🤣"))
        listOf(symbolsView.accessibilityCells(), emojiView.accessibilityCells()).forEach { cells ->
            val labels = cells.map { it.description.toString() }
            assertTrue(
                "the space key should be a node with a label: $labels",
                context.getString(R.string.key_space) in labels
            )
            assertTrue(
                "the backspace should be a word: $labels",
                context.getString(R.string.tpad_backspace) in labels
            )
            assertFalse("the arrow glyph is not a label: $labels", labels.any { it == BottomBarSpec.BACKSPACE_GLYPH })
            assertTrue("the enter key should be a word: $labels", labels.any { it == "Enter" })
        }
    }

    @Test
    fun `every emoji on screen is a node that selects its emoji`() {
        val list = listOf("😀", "😁", "😂", "🤣", "😅", "😄", "😆", "😇")
        val view = emoji(list)
        var selected: String? = null
        view.onSelectEmoji = { selected = it }

        val cells = view.accessibilityCells()
        val emojiNodes = cells.filter { cell -> cell.description.toString() in list }
        // Eight emoji over seven columns is two rows, both inside the viewport
        // on a 255dp panel.
        assertEquals(list.size, emojiNodes.size)
        assertEquals("😀", emojiNodes.first().description.toString())

        emojiNodes.first().onActivate()
        assertEquals("😀", selected)
    }

    @Test
    fun `the nodes move with the grid, because a node left behind describes nothing`() {
        val many = (1..200).map { "x$it" }
        val view = symbols(many)
        val cardPadding = 1.5f * context.density
        assertTrue(
            "x1 should be a node before the grid scrolls",
            view.accessibilityCells().any { it.description.toString() == "x1" }
        )

        // Three rows down, the first row is off screen and the fourth is at the
        // top of the grid.
        view.scrollOffset = view.cellSize * 3f
        val cells = view.accessibilityCells()
        val firstVisible = cellLabelled(cells, "x22")
        assertEquals(
            "x22 is the first symbol of the first visible row",
            view.gridTop + cardPadding,
            firstVisible.bounds.top,
            0.01f
        )
        assertTrue(
            "a symbol scrolled off the top should not still be a node",
            cells.none { it.description.toString() == "x1" }
        )
        // The nodes are the drawn cells, which is what the panel itself walks
        // over: two windows over one grid would be free to drift apart.
        assertEquals(
            view.visibleSymbolIndices().count(),
            cells.count { cell -> cell.description.toString().startsWith("x") }
        )
    }

    @Test
    fun `a node is where its key is, and a touch on a node finds that node`() {
        val view = pad()
        val host = KeyGridTouchHelper(View(context)) { view.accessibilityCells() }
        val cells = host.cells()
        assertEquals(16, cells.size)

        cells.forEachIndexed { index, cell ->
            assertEquals(
                "a touch on cell $index found another cell",
                index,
                host.indexAt(cell.bounds.centerX(), cell.bounds.centerY())
            )
        }
        assertEquals(KeyGridTouchHelper.NO_CELL, host.indexAt(-10f, -10f))
    }

    @Test
    fun `nodes do not overlap each other, and sit inside the panel`() {
        val pad = pad()
        val symbolView = symbols(PickerData.SYMBOLS_MAP[1] ?: emptyList())
        val emojiView = emoji(listOf("😀", "😁", "😂", "😄", "😅", "😆", "😅", "🤣"))

        val panels = listOf(
            Panel("tpad", pad, pad.accessibilityCells()),
            Panel("symbols", symbolView, symbolView.accessibilityCells()),
            Panel("emoji", emojiView, emojiView.accessibilityCells())
        )
        panels.forEach { panel ->
            val name = panel.name
            val view = panel.view
            val cells = panel.cells
            assertTrue("$name should have nodes", cells.isNotEmpty())
            for (i in cells.indices) {
                val a = cells[i].bounds
                assertTrue("$name node $i has no size", a.width() > 0f && a.height() > 0f)
                assertTrue("$name node $i is outside the panel", a.left >= -0.01f && a.top >= -0.01f)
                assertTrue("$name node $i is outside the panel", a.right <= view.width + 0.01f)
                assertTrue("$name node $i is outside the panel", a.bottom <= view.height + 0.01f)
                for (j in i + 1 until cells.size) {
                    val b = cells[j].bounds
                    // On copies: the framework's intersect empties the rect it
                    // is called on, so testing the real one would leave a
                    // zeroed rect behind that passes every comparison after it.
                    assertFalse(
                        "$name nodes $i and $j overlap",
                        RectF(a).intersects(b.left, b.top, b.right, b.bottom)
                    )
                }
            }
        }
    }

    private data class Panel(
        val name: String,
        val view: View,
        val cells: List<KeyGridCell>
    )
}
