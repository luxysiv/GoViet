package com.goviet.keyboard.ui

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The shared bottom row, against the copy of it it replaced.
 *
 * The emoji panel and the symbol picker used to lay out the same six keys
 * separately, with the same weights and their own version of the same
 * arithmetic. [BottomBarSpec] is now the one description of that row, so the
 * thing worth proving is that it lays keys out where the old code did — key for
 * key — on every panel shape the app uses. A refactor that moves the bar is a
 * regression the user feels as the whole keyboard shifting under their thumbs.
 *
 * Robolectric, because the keys are laid out into real [RectF]s.
 */
@RunWith(RobolectricTestRunner::class)
class BottomBarSpecTest {

    /**
     * The emoji panel's old bottom-row layout, copied from the version that
     * shipped, as the oracle for the new spec. Four of the panel's dp figures
     * are spelled out here on purpose: if [KeyGeometry] is retuned later, this
     * is the copy that will disagree, which is the point.
     */
    private fun legacyBottomRow(
        weights: List<Float>,
        widthPx: Float,
        heightPx: Float,
        density: Float
    ): List<RectF> {
        val horizontalSpacing = 4.5f * density
        val verticalSpacing = 7.0f * density
        val paddingLeft = 4f * density
        val paddingRight = 4f * density
        val paddingBottom = 4f * density

        val usableWidth = widthPx - paddingLeft - paddingRight
        val bottomRowHeight = (heightPx - 6f * density - 4f * density - verticalSpacing * 4) / 5f
        val bottomContainerTop = heightPx - bottomRowHeight - paddingBottom
        val bottomContainerBottom = heightPx - paddingBottom

        val totalSpacings = weights.size - 1
        val widthAvailable = usableWidth - (horizontalSpacing * totalSpacings)
        val totalWeight = weights.sum()
        val unitWidth = widthAvailable / totalWeight

        val out = mutableListOf<RectF>()
        var curX = paddingLeft
        for (weight in weights) {
            val keyWidth = weight * unitWidth
            out.add(RectF(curX, bottomContainerTop, curX + keyWidth, bottomContainerBottom))
            curX += keyWidth + horizontalSpacing
        }
        return out
    }

    private fun layOut(
        spec: BottomBarSpec,
        widthPx: Int,
        heightPx: Int,
        density: Float
    ): List<Key> {
        val keys = spec.buildKeys()
        spec.layOut(
            keys = keys,
            widthPx = widthPx,
            heightPx = heightPx,
            density = density,
            rowCount = BottomBarSpec.ROW_COUNT,
            verticalSpacingPx = 7f * density
        )
        return keys
    }

    private fun assertMatchesLegacy(spec: BottomBarSpec, widthPx: Int, heightPx: Int, density: Float) {
        val keys = layOut(spec, widthPx, heightPx, density)
        val expected = legacyBottomRow(
            weights = keys.map { it.weight },
            widthPx = widthPx.toFloat(),
            heightPx = heightPx.toFloat(),
            density = density
        )
        assertEquals(6, keys.size)
        assertEquals(expected.size, keys.size)
        keys.forEachIndexed { i, key ->
            val e = expected[i]
            val where = "${spec.switchKeyCode} ${widthPx}x$heightPx@$density key#$i"
            assertEquals("$where left", e.left, key.rect.left, 0.01f)
            assertEquals("$where top", e.top, key.rect.top, 0.01f)
            assertEquals("$where right", e.right, key.rect.right, 0.01f)
            assertEquals("$where bottom", e.bottom, key.rect.bottom, 0.01f)
        }
    }

    @Test
    fun `the bottom bar lands where the hand-written layout put it`() {
        val spec = BottomBarSpec.forEmoji(enterLabel = "⏎")
        assertMatchesLegacy(spec, 1080, 765, 3f)   // 360x255dp phone portrait
        assertMatchesLegacy(spec, 1080, 1020, 3f)  // 360x340dp tall phone
        assertMatchesLegacy(spec, 1680, 840, 2f)   // 840x420dp tablet
        assertMatchesLegacy(spec, 1920, 570, 3f)   // 640x190dp phone landscape
        assertMatchesLegacy(spec, 2048, 480, 2f)   // 1024x240dp landscape
    }

    @Test
    fun `the emoji and symbol bars occupy the same rects, and differ only in the middle key`() {
        val density = 3f
        val emojiKeys = layOut(BottomBarSpec.forEmoji("⏎"), 1080, 765, density)
        val symbolKeys = layOut(BottomBarSpec.forSymbols("⏎"), 1080, 765, density)

        emojiKeys.forEachIndexed { i, a ->
            val b = symbolKeys[i]
            assertEquals(a.rect.left, b.rect.left, 0.01f)
            assertEquals(a.rect.top, b.rect.top, 0.01f)
            assertEquals(a.rect.right, b.rect.right, 0.01f)
            assertEquals(a.rect.bottom, b.rect.bottom, 0.01f)
        }

        assertEquals("ABC", symbolKeys[0].code)
        assertEquals(",", symbolKeys[1].code)
        assertEquals("EMOJI", symbolKeys[2].code)
        assertEquals("SPACE", symbolKeys[3].code)
        assertEquals("BACKSPACE", symbolKeys[4].code)
        assertEquals("ENTER", symbolKeys[5].code)

        // From emoji the middle key is the symbol switch, and vice versa.
        assertEquals("!?#", emojiKeys[2].code)
        assertEquals("!?#", emojiKeys[2].label)
        assertEquals("🙂", symbolKeys[2].label)
    }

    @Test
    fun `the keys tile the width exactly, with no overflow and no overlap`() {
        val density = 3f
        val widthPx = 1080
        val keys = layOut(BottomBarSpec.forSymbols("⏎"), widthPx, 765, density)
        val padding = KeyGeometry.panelPaddingPx(density)
        val spacing = KeyGeometry.rowSpacingPx(density)

        // The spec, restated: one weight unit is the width left after the two
        // paddings and the five gaps, split by the sum of the weights.
        val totalWeight = keys.sumOf { it.weight.toDouble() }.toFloat()
        val unit = (widthPx - 2f * padding - spacing * (keys.size - 1)) / totalWeight

        assertEquals(
            "first key should start at the left padding",
            padding,
            keys.first().rect.left,
            0.01f
        )
        keys.forEachIndexed { i, key ->
            val expectedLeft = padding + (0 until i).sumOf { keys[it].weight.toDouble() }.toFloat() * unit + spacing * i
            assertEquals("key $i (${key.code}) left", expectedLeft, key.rect.left, 0.01f)
            assertEquals(
                "key $i (${key.code}) width",
                key.weight * unit,
                key.rect.width(),
                0.01f
            )
        }
        // Tiling means the row fills the panel exactly: the widths add up to the
        // available width and every gap between them is paid for, so the last
        // key ends on the right padding.
        assertEquals(
            "row should fill the panel to the right padding",
            widthPx - padding,
            keys.last().rect.right,
            0.01f
        )
        keys.zipWithNext { a, b ->
            assertTrue(
                "keys overlap at ${a.code}->${b.code}: ${a.rect} then ${b.rect}",
                b.rect.left >= a.rect.right - 0.01f
            )
            assertEquals(
                "gap between ${a.code} and ${b.code} is not the shared spacing",
                spacing,
                b.rect.left - a.rect.right,
                0.01f
            )
        }
        keys.forEach { key ->
            assertTrue(
                "key ${key.code} runs off the panel: ${key.rect}",
                key.rect.right <= widthPx - padding + 0.01f
            )
            assertTrue("key ${key.code} sits above the panel: ${key.rect}", key.rect.top >= 0f)
            assertTrue("key ${key.code} is not drawn in the panel: ${key.rect}", key.rect.bottom <= 765f)
        }
        // Space is the widest key, which is what makes the bar readable.
        assertTrue("space should be the widest key", keys[3].rect.width() > keys[0].rect.width())
        assertTrue("space should be the widest key", keys[3].rect.width() > keys[4].rect.width())
    }

    @Test
    fun `a panel with no size leaves the keys alone`() {
        val spec = BottomBarSpec.forEmoji(enterLabel = "⏎")
        val keys = spec.buildKeys()
        val before = keys.map { RectF(it.rect) }
        assertEquals(0f, spec.layOut(keys, 0, 0, 3f, BottomBarSpec.ROW_COUNT, 21f), 0.01f)
        keys.forEachIndexed { i, key -> assertEquals(before[i], key.rect) }
    }

    @Test
    fun `the row count and weights are the ones that keep the bar in place`() {
        // Five is not a typo: the bar is sized as if the letter keyboard's five
        // rows sat above it, so it sits where it has always sat. Dividing the
        // height by one would make the bar half the panel tall.
        assertEquals(5, BottomBarSpec.ROW_COUNT)
        assertEquals(
            9.3f,
            BottomBarSpec.ABC + BottomBarSpec.COMMA + BottomBarSpec.SWITCH +
                BottomBarSpec.SPACE + BottomBarSpec.BACKSPACE + BottomBarSpec.ENTER,
            0.0001f
        )
    }
}
