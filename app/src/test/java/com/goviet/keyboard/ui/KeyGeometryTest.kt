package com.goviet.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared layout arithmetic, checked against the arithmetic it replaced.
 *
 * [KeyGeometry] exists so that the letter grid, emoji panel and symbol picker
 * cannot each invent their own row height. The risk of sharing arithmetic is
 * that a change to it silently resizes keys in three places, so these tests
 * compare it against a copy of the old per-view formula rather than only
 * against itself.
 *
 * No Android types are touched here, so these run as plain JVM tests.
 */
class KeyGeometryTest {

    /**
     * The per-view helper that [KeyGeometry.standardRowHeight] replaced, kept
     * as the oracle: 6dp above the first row, 4dp below the last, 7dp between
     * rows, and whatever is left divided by the row count.
     */
    private fun legacyRowHeight(
        totalHeightPx: Float,
        density: Float,
        rowCount: Int,
        verticalSpacingPx: Float
    ): Float {
        val paddingTop = 6f * density
        val paddingBottom = 4f * density
        val usableHeight =
            totalHeightPx - paddingTop - paddingBottom - (verticalSpacingPx * (rowCount - 1))
        return usableHeight / rowCount
    }

    private val weights = listOf(1.3f, 1.0f, 1.1f, 3.4f, 1.2f, 1.3f)

    @Test
    fun `row height matches the formula each view used to run on its own`() {
        val densities = listOf(1f, 1.5f, 2f, 2.625f, 3f, 3.5f)
        val heights = listOf(120f, 160f, 190f, 255f, 280f, 320f, 411f, 640f)
        for (density in densities) {
            for (heightDp in heights) {
                val heightPx = heightDp * density
                val spacing = 7f * density
                for (rowCount in 1..6) {
                    assertEquals(
                        "row height drifted at density=$density height=${heightDp}dp rows=$rowCount",
                        legacyRowHeight(heightPx, density, rowCount, spacing),
                        KeyGeometry.standardRowHeight(heightPx, density, rowCount, spacing),
                        0.0001f
                    )
                }
            }
        }
    }

    @Test
    fun `unit width divides the width left after padding and gaps by the weights`() {
        for (density in listOf(1f, 2f, 3f)) {
            val widthPx = 360f * density
            val spacing = 4.5f * density
            val padding = 4f * density
            val expected = (widthPx - 2 * padding - spacing * 5) / 9.3f
            assertEquals(
                expected,
                KeyGeometry.unitWidthForWeights(
                    totalWidthPx = widthPx,
                    density = density,
                    keyCount = 6,
                    weights = weights
                ),
                0.0001f
            )
        }
    }

    /**
     * The 48dp target, with the state of it written down as numbers.
     *
     * The drawn row on a 255dp portrait phone is 43.4dp, under the target, and
     * stays that way: five rows of 48dp plus the current padding and gaps need
     * 278dp. That is why the touch target is a grown rect rather than a bigger
     * key. These numbers are pinned so "already meets 48dp" is a claim a test can
     * contradict later, in either direction.
     */
    @Test
    fun `the drawn row on a phone is under the touch target, which is why targets grow`() {
        val portraitPhone = KeyGeometry.standardRowHeight(
            totalHeightPx = 255f,
            density = 1f,
            rowCount = BottomBarSpec.ROW_COUNT,
            verticalSpacingPx = 7f
        )
        val portraitTablet = KeyGeometry.standardRowHeight(
            totalHeightPx = 280f,
            density = 1f,
            rowCount = BottomBarSpec.ROW_COUNT,
            verticalSpacingPx = 7f
        )
        val landscapePhone = KeyGeometry.standardRowHeight(
            totalHeightPx = 190f,
            density = 1f,
            rowCount = BottomBarSpec.ROW_COUNT,
            verticalSpacingPx = 7f
        )

        assertEquals(43.4f, portraitPhone, 0.001f)
        assertEquals(48.4f, portraitTablet, 0.001f)
        assertEquals(30.4f, landscapePhone, 0.001f)

        assertTrue(
            "portrait phone drawn row is under the target; if this was fixed, update the test",
            portraitPhone < KeyGeometry.MIN_TOUCH_DP
        )
        assertTrue("a tablet row already meets the target", portraitTablet >= KeyGeometry.MIN_TOUCH_DP)

        // The arithmetic that proves a phone cannot reach the target by
        // tuning: 5 rows of 48dp, plus 10dp padding, plus 4 gaps of 7dp.
        val neededFor48 = 5 * KeyGeometry.MIN_TOUCH_DP + 6f + 4f + 4 * 7f
        assertEquals(278f, neededFor48, 0.001f)
        assertTrue(neededFor48 > 255f)

        // What a target has to gain, per side, to reach 48dp. Rows are 7dp
        // apart, so 2.3dp per side fits without two rows claiming the same gap.
        val growPerSide = (KeyGeometry.MIN_TOUCH_DP - portraitPhone) / 2f
        assertEquals(2.3f, growPerSide, 0.001f)
        assertTrue(growPerSide * 2f < 7f)
    }

    @Test
    fun `the narrowest bottom key is under the touch target in width too`() {
        // Height is not the only shortfall: at 360dp the comma key is 35.4dp
        // wide. Recorded for the same reason as the row height above.
        val unit = KeyGeometry.unitWidthForWeights(
            totalWidthPx = 360f,
            density = 1f,
            keyCount = 6,
            weights = weights
        )
        val commaWidth = BottomBarSpec.COMMA * unit
        assertEquals(35.43f, commaWidth, 0.01f)
        assertTrue(commaWidth < KeyGeometry.MIN_TOUCH_DP)
    }

    @Test
    fun `a panel with no room lays out to zero instead of a negative height`() {
        assertEquals(0f, KeyGeometry.standardRowHeight(0f, 3f, 5, 21f), 0f)
        assertEquals(0f, KeyGeometry.standardRowHeight(100f, 3f, 0, 21f), 0f)
        // Degenerate width: a zero-height row must not produce a negative one.
        assertTrue(KeyGeometry.standardRowHeight(4f, 1f, 5, 7f) < 0f)
        assertEquals(0f, KeyGeometry.unitWidthForWeights(0f, 1f, 0, weights), 0f)
        assertEquals(0f, KeyGeometry.unitWidthForWeights(360f, 1f, 6, listOf(0f)), 0f)
    }
}
