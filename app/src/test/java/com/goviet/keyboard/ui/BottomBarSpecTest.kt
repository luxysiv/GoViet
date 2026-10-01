package com.goviet.keyboard.ui

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BottomBarSpecTest {

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
        assertEquals(spec.buildKeys().size, keys.size)
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
        val spec = BottomBarSpec.forSymbols(enterLabel = "⏎")
        assertMatchesLegacy(spec, 1080, 765, 3f)   // 360x255dp phone portrait
        assertMatchesLegacy(spec, 1080, 1020, 3f)  // 360x340dp tall phone
        assertMatchesLegacy(spec, 1680, 840, 2f)   // 840x420dp tablet
        assertMatchesLegacy(spec, 1920, 570, 3f)   // 640x190dp phone landscape
        assertMatchesLegacy(spec, 2048, 480, 2f)   // 1024x240dp landscape
    }

    @Test
    fun `the emoji panel has 5 keys without comma and space matching qwerty`() {
        val density = 3f
        val emojiKeys = layOut(BottomBarSpec.forEmoji("⏎"), 1080, 765, density)

        assertEquals(5, emojiKeys.size)
        assertEquals("ABC", emojiKeys[0].code)
        assertEquals("SYM", emojiKeys[1].code)
        assertEquals("?123", emojiKeys[1].label)
        assertEquals("SPACE", emojiKeys[2].code)
        assertEquals(5.5f, emojiKeys[2].weight, 0.001f)
        assertEquals("BACKSPACE", emojiKeys[3].code)
        assertEquals("ENTER", emojiKeys[4].code)
    }

    @Test
    fun `the symbol bar has 6 keys with comma`() {
        val density = 3f
        val symbolKeys = layOut(BottomBarSpec.forSymbols("⏎"), 1080, 765, density)

        assertEquals(6, symbolKeys.size)
        assertEquals("ABC", symbolKeys[0].code)
        assertEquals(",", symbolKeys[1].code)
        assertEquals("EMOJI", symbolKeys[2].code)
        assertEquals("🙂", symbolKeys[2].label)
        assertEquals("SPACE", symbolKeys[3].code)
        assertEquals("BACKSPACE", symbolKeys[4].code)
        assertEquals("ENTER", symbolKeys[5].code)
    }

    @Test
    fun `the keys tile the width exactly, with no overflow and no overlap`() {
        val density = 3f
        val widthPx = 1080
        val keys = layOut(BottomBarSpec.forSymbols("⏎"), widthPx, 765, density)
        val padding = KeyGeometry.panelPaddingPx(density)
        val spacing = KeyGeometry.rowSpacingPx(density)

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
        assertEquals(5, BottomBarSpec.ROW_COUNT)
        assertEquals(
            9.3f,
            BottomBarSpec.ABC + BottomBarSpec.COMMA + BottomBarSpec.SWITCH +
                BottomBarSpec.SPACE + BottomBarSpec.BACKSPACE + BottomBarSpec.ENTER,
            0.0001f
        )
        assertEquals(
            10.7f,
            BottomBarSpec.ABC_5KEY + BottomBarSpec.SWITCH_5KEY +
                BottomBarSpec.SPACE_5KEY + BottomBarSpec.BACKSPACE_5KEY + BottomBarSpec.ENTER_5KEY,
            0.0001f
        )
    }
}
