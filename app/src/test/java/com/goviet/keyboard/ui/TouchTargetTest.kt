package com.goviet.keyboard.ui

import android.content.Context
import android.view.View
import com.goviet.core.density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.cos
import kotlin.math.sin

/**
 * Touch targets: what a finger has to hit, as opposed to what is drawn.
 *
 * Nothing is drawn any bigger. On a 255dp portrait phone a row is 43.4dp tall
 * and a letter key 35.4dp wide, both under the 48dp a thumb can hit, and five
 * 48dp rows plus the current padding and gaps would need a 278dp panel. So the
 * target a key answers to is its rect grown to [KeyGeometry.MIN_TOUCH_DP]
 * around its centre, with the key the finger was aimed at winning where two
 * grown targets meet.
 *
 * The guarantee pinned here is the one a thumb needs: no point within 48dp of
 * a key's drawn centre is dead, and a tap on the drawn key still hits that key.
 * Where a 48dp target would have two keys claiming the same pixel, the drawn
 * key under the finger wins instead, so the user gets the key they pressed.
 */
@RunWith(RobolectricTestRunner::class)
class TouchTargetTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val minTouch: Float get() = KeyGeometry.MIN_TOUCH_DP

    private fun hit(keys: List<Key>, x: Float, y: Float): Key? =
        findKeyAt(keys, x, y, KeyGeometry.minTouchPx(context.density))

    /** Where a key is actually painted, which is what a finger aims at. */
    private fun drawnCentreX(key: Key) = (key.visualRect.left + key.visualRect.right) / 2f

    private fun drawnCentreY(key: Key) = (key.visualRect.top + key.visualRect.bottom) / 2f

    private fun bottomBar(widthDp: Float = 360f, heightDp: Float = 255f): List<Key> {
        val density = context.density
        val spec = BottomBarSpec.forSymbols(enterLabel = "⏎")
        val keys = spec.buildKeys()
        spec.layOut(
            keys = keys,
            widthPx = (widthDp * density).toInt(),
            heightPx = (heightDp * density).toInt(),
            density = density,
            rowCount = BottomBarSpec.ROW_COUNT,
            verticalSpacingPx = 7f * density
        )
        return keys
    }

    private inline fun <T : View> laidOut(view: T, widthDp: Float, heightDp: Float): T {
        val density = context.density
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, widthPx, heightPx)
        return view
    }

    /**
     * No sample point out to 48dp from a key's drawn centre may hit nothing.
     *
     * 24dp is the target radius and it is included, so the samples sit exactly
     * on it: a target that claimed 48dp and left its own edge out would be
     * 47.99dp of target wearing a 48dp label.
     */
    private fun assertNoDeadZoneAroundDrawnKeys(keys: List<Key>, label: String) {
        val reach = minTouch * context.density / 2f
        keys.forEach { key ->
            val centreX = drawnCentreX(key)
            val centreY = drawnCentreY(key)
            for (step in 0 until 16) {
                val angle = step * (Math.PI * 2 / 16)
                val x = centreX + reach * cos(angle).toFloat()
                val y = centreY + reach * sin(angle).toFloat()
                assertNotNull(
                    "$label: ${key.code} is dead at ($x, $y)",
                    hit(keys, x, y)
                )
            }
        }
    }

    @Test
    fun `no dead point within 48dp of a bottom bar key`() {
        assertNoDeadZoneAroundDrawnKeys(bottomBar(), "bottom bar")
    }

    @Test
    fun `no dead point within 48dp of a letter key`() {
        val keys = laidOut(StandardLetterGridView(context), 360f, 255f).laidOutKeys()
        assertTrue("expected the letter grid to have keys, got ${keys.size}", keys.size >= 30)
        assertNoDeadZoneAroundDrawnKeys(keys, "letter grid")
    }

    @Test
    fun `a tap on a drawn key hits that key, in every panel`() {
        val bottom = bottomBar()
        bottom.forEach {
            assertEquals(it, hit(bottom, drawnCentreX(it), drawnCentreY(it)))
        }

        val grid = laidOut(StandardLetterGridView(context), 360f, 255f).laidOutKeys()
        grid.forEach {
            assertEquals(it, hit(grid, drawnCentreX(it), drawnCentreY(it)))
        }
    }

    @Test
    fun `a row answers 2dp above its drawn top edge, which used to be dead`() {
        val density = context.density
        val keys = bottomBar()
        val key = keys.first()
        val grow = (minTouch * density - key.rect.height()) / 2f

        // The row is drawn 43.4dp, so it has to grow 2.3dp a side to be 48dp,
        // and rows are 7dp apart, so the growth fits without two rows meeting.
        assertTrue("a phone row is expected to be under the target", key.rect.height() < minTouch * density)
        assertEquals(2.3f * density, grow, 0.05f * density)
        assertTrue(grow * 2f < 7f * density)

        assertEquals(key, hit(keys, drawnCentreX(key), key.rect.top - 2f * density))
        assertNull("3dp above the row is past the target", hit(keys, drawnCentreX(key), key.rect.top - 3f * density))
    }

    @Test
    fun `a touch in a gap between two keys goes to the nearer one`() {
        val keys = bottomBar()
        val a = keys[1]
        val b = keys[2]
        val y = drawnCentreY(a)
        val mid = (a.rect.right + b.rect.left) / 2f
        assertEquals(a, hit(keys, mid - 1.5f * context.density, y))
        assertEquals(b, hit(keys, mid + 1.5f * context.density, y))
    }

    @Test
    fun `a key grows by exactly what it lacks, and no further`() {
        val density = context.density
        val keys = bottomBar()
        val first = keys.first()
        val y = drawnCentreY(first)
        val grow = (minTouch * density - first.rect.width()).coerceAtLeast(0f) / 2f

        // 46dp key, so under a dp of growth: it reaches just past its own edge
        // and leaves the rest of the 4dp panel padding alone. Padding every key
        // out to 48dp instead would take away space the keyboard is using.
        assertTrue("expected a key under the target", first.rect.width() < minTouch * density)
        assertTrue(
            "the target should not swallow the padding",
            grow < KeyGeometry.PANEL_PADDING_DP * density
        )
        assertEquals(first, hit(keys, first.rect.left - (grow - 0.1f), y))
        assertNull(hit(keys, first.rect.left - (grow + 0.5f), y))

        // The narrowest key does reach much further, over the space its
        // neighbours occupy — which is why the gap test above matters.
        val narrowest = keys.minByOrNull { it.rect.width() }!!
        val narrowestGrow = (minTouch * density - narrowest.rect.width()) / 2f
        assertTrue("expected a 35dp key to need real growth", narrowestGrow > 6f * density)
        assertNotNull(
            "a touch inside the neighbour, past the narrow key's target, still hits a key",
            hit(keys, narrowest.rect.right + narrowestGrow - 0.1f, y)
        )
    }

    @Test
    fun `the letter grid has no dead padding because its hit rects already tile the panel`() {
        val keys = laidOut(StandardLetterGridView(context), 360f, 255f).laidOutKeys()
        val topRow = keys.filter { it.rect.top == 0f }.sortedBy { it.rect.left }
        assertTrue(topRow.isNotEmpty())
        // Its hit rects already reach the panel edge, so the drawn 4dp margin is
        // hittable. This is the letter grid's own midpoint expansion, not the
        // grown target, and it is why the letter grid never had dead space.
        assertEquals(0f, topRow.first().rect.left, 0.001f)
        assertNotNull(hit(keys, 1f, topRow.first().rect.top + 1f))
    }

    @Test
    fun `a touch well outside every key hits nothing`() {
        val density = context.density
        val keys = bottomBar()
        assertNull(hit(keys, 180f * density, keys.first().rect.top - 40f * density))
        assertNull(hit(keys, 180f * density, keys.last().rect.bottom + 20f * density))
    }

    @Test
    fun `symbol cells clear 48dp because the grid is seven columns`() {
        val density = context.density
        val view = SymbolsPickerGridView(context)
        val symbols = PickerData.SYMBOLS_MAP[1] ?: error("tab 1 is missing")
        view.symbolsList = symbols
        laidOut(view, 360f, 255f)

        assertEquals(7, view.cols)
        assertTrue(
            "a symbol cell is ${view.cellSize / density}dp on a 360dp panel",
            view.cellSize >= minTouch * density
        )
        // The largest tab still fits in four rows, so nothing is off the panel.
        assertEquals(27, symbols.size)
        assertEquals(4, view.rowCount)
    }

    @Test
    fun `emoji cells clear 48dp, and each side of a cell boundary hits its own cell`() {
        val density = context.density
        val view = laidOut(TraditionalEmojiView(context), 360f, 255f)
        view.emojisList = listOf("😀", "😃", "😄", "😁", "😆", "😅", "😂")

        val cell = view.colW
        val rowCentreY = view.emojiAreaTop + cell / 2f
        assertEquals(50.3f, cell / density, 0.2f)
        assertTrue("an emoji cell is expected to clear the target", cell >= minTouch * density)

        for (i in 0 until 7) {
            val x = view.emojiAreaLeft + cell * i + cell / 2f
            assertEquals("emoji cell $i", i, view.findEmojiIndexAt(x, rowCentreY))
        }
        val boundary = view.emojiAreaLeft + cell
        assertEquals(0, view.findEmojiIndexAt(boundary - 3f * density, rowCentreY))
        assertEquals(1, view.findEmojiIndexAt(boundary + 3f * density, rowCentreY))

        // The area's own bounds stay exact: the side padding is not an emoji.
        assertEquals(-1, view.findEmojiIndexAt(view.emojiAreaLeft - 2f, rowCentreY))

        // The control row is judged the same way a press is, so a touch on the
        // space bar finds it, and the top of the panel is not a control key.
        val rowTop = 255f * density - KeyGeometry.PANEL_PADDING_DP * density - 43.4f * density
        assertEquals(2, view.findBottomKeyIndexAt(180f * density, rowTop + 21.7f * density))
        assertEquals(-1, view.findBottomKeyIndexAt(180f * density, 8f * density))
    }
}
