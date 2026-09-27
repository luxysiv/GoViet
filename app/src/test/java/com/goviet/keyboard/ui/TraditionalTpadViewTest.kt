package com.goviet.keyboard.ui

import android.content.Context
import android.text.InputType
import android.view.View
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
 * The T-pad, which is the one panel that laid its own keys out.
 *
 * Three things are pinned here. The digits carry the letters printed on phone
 * keypads, because "2 abc" is how the finger finds 2 without looking. The
 * bottom row sits where every other panel's bottom row sits, which is what
 * stopped being true when this panel divided its own height by hand. And the
 * drag-along-backspace gesture does not delete a whole word in a field that
 * asked for numbers.
 */
@RunWith(RobolectricTestRunner::class)
class TraditionalTpadViewTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /**
     * Slack for geometry asserted twice: once from the layout, once from the
     * formula it has to agree with. Both are float arithmetic over a panel
     * height in pixels, so they agree to well under a tenth of a pixel.
     */
    private val tolerance = 0.05f

    private fun dp(value: Float): Int = (value * context.density).toInt()

    private val numberField = InputType.TYPE_CLASS_NUMBER
    private val phoneField = InputType.TYPE_CLASS_PHONE
    private val textField = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL

    private fun pad(
        inputType: Int = numberField,
        isOtp: Boolean = false,
        widthDp: Float = 360f,
        heightDp: Float = 255f
    ): TraditionalTpadView {
        val view = TraditionalTpadView(context)
        val widthPx = dp(widthDp)
        val heightPx = dp(heightDp)
        view.currentEditorInputType = inputType
        view.currentInputType = inputType
        view.isOtpField = isOtp
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, widthPx, heightPx)
        return view
    }

    private fun key(view: TraditionalTpadView, code: String): Key =
        view.laidOutKeys().firstOrNull { it.code == code } ?: error("no $code key")

    @Test
    fun `a digit shows the digit, and nothing under it`() {
        // The letters a phone keypad prints under its digits were the reason a
        // key had two rows on it: a 21dp digit with 11dp of letters under it, in
        // a key 48dp tall. Nothing types abc from a 2 here, so the key is a
        // label again, centred, the same as every other key on the pad.
        listOf(numberField, phoneField).forEach { inputType ->
            val view = pad(inputType = inputType)
            for (digit in '2'..'9') {
                val found = key(view, digit.toString())
                assertEquals("label on $digit", digit.toString(), found.label)
                assertEquals("a second row of text on $digit", null, found.secondaryLabel)
            }
        }
    }

    @Test
    fun `1 carries punctuation by long press, and no digit does`() {
        val one = key(pad(), "1")
        // The other digits cannot be reached that way: a long press on 2 types
        // a 2, and long pressing it for punctuation is how a phone keypad
        // stopped being a phone keypad.
        val two = key(pad(), "2")
        assertEquals(null, two.longPressOptions)
        // 44dp per option, so eight is the most a 360dp phone can show.
        assertTrue(
            "the popup would be wider than the phone",
            one.longPressOptions!!.size * 44f * context.density <= 360f * context.density
        )
        assertTrue(one.longPressOptions!!.contains("="))
        assertTrue(one.longPressOptions!!.contains("@"))
    }

    @Test
    fun `the pad is four rows of four`() {
        val view = pad()
        assertEquals(16, view.laidOutKeys().size)
        val rows = view.laidOutKeys().groupBy { it.rect.top }
        assertEquals(4, rows.size)
        rows.values.forEach { assertEquals(4, it.size) }
    }

    @Test
    fun `the bottom row ends where every other panel's bottom row ends`() {
        val density = context.density
        val view = pad()
        val keys = view.laidOutKeys()
        val bottomRow = keys.filter { it.rect.bottom == keys.maxOf { k -> k.rect.bottom } }
        assertEquals(4, bottomRow.size)

        val expectedRowHeight = KeyGeometry.standardRowHeight(
            totalHeightPx = view.height.toFloat(),
            density = density,
            rowCount = 4,
            verticalSpacingPx = KeyGeometry.rowSpacingPx(density)
        )
        // The same expression BottomBarSpec lays a shared bottom bar out with.
        assertEquals(
            view.height - KeyGeometry.panelPaddingPx(density) - expectedRowHeight,
            bottomRow.first().rect.top,
            tolerance
        )
        assertEquals(view.height - KeyGeometry.panelPaddingPx(density), bottomRow.first().rect.bottom, tolerance)
    }

    @Test
    fun `rows are spaced and padded the way the shared geometry says`() {
        val density = context.density
        val view = pad()
        val keys = view.laidOutKeys().sortedBy { it.rect.top }
        val padding = KeyGeometry.panelPaddingPx(density)
        val spacing = KeyGeometry.rowSpacingPx(density)

        assertEquals(padding, keys.first().rect.left, tolerance)
        assertEquals(view.width - padding, keys.maxOf { it.rect.right }, tolerance)

        val rowTops = keys.map { it.rect.top }.distinct()
        assertEquals(4, rowTops.size)
        val rowHeight = keys.first().rect.height()
        rowTops.forEach { assertEquals(rowHeight, keys.first { k -> k.rect.top == it }.rect.height(), tolerance) }
        for (index in 1 until rowTops.size) {
            assertEquals(
                "gap between row ${index - 1} and $index",
                spacing,
                rowTops[index] - (rowTops[index - 1] + rowHeight),
                tolerance
            )
        }
    }

    @Test
    fun `keys do not overlap and stay inside the panel`() {
        listOf(360f to 255f, 1024f to 160f).forEach { (widthDp, heightDp) ->
            val view = pad(widthDp = widthDp, heightDp = heightDp)
            val keys = view.laidOutKeys()
            for (index in keys.indices) {
                val a = keys[index].rect
                assertTrue("key $index is outside the panel", a.left >= -tolerance && a.top >= -tolerance)
                assertTrue("key $index is outside the panel", a.right <= view.width + tolerance)
                assertTrue("key $index is outside the panel", a.bottom <= view.height + tolerance)
                for (other in keys.drop(index + 1)) {
                    assertFalse("keys ${index} and ${keys.indexOf(other)} overlap", a.intersect(other.rect))
                }
            }
        }
    }

    @Test
    fun `what is drawn on a key is the key itself`() {
        val view = pad()
        view.laidOutKeys().forEach { key ->
            // The hand layout drew the key 2dp inside a cell that spanned the
            // gap, so the drawn key and the key the touch test answered for
            // were two different rectangles.
            assertEquals(key.rect.left, key.visualRect.left, tolerance)
            assertEquals(key.rect.top, key.visualRect.top, tolerance)
            assertEquals(key.rect.right, key.visualRect.right, tolerance)
            assertEquals(key.rect.bottom, key.visualRect.bottom, tolerance)
        }
    }

    @Test
    fun `dragging backspace does not take a whole word in a numeric field`() {
        val view = pad(inputType = numberField)
        assertFalse("a number field must not lose a whole number to a swipe", view.allowsWordDelete)

        view.currentEditorInputType = phoneField
        assertFalse("a phone field must not lose a whole number to a swipe", view.allowsWordDelete)
    }

    @Test
    fun `dragging backspace does not take a whole word in a code field`() {
        val view = pad(inputType = textField, isOtp = true)
        assertFalse("a one-time code must not lose a whole code to a swipe", view.allowsWordDelete)
    }

    @Test
    fun `the gesture is still there when the pad was opened by hand`() {
        // The T-pad is reachable from the letter keyboard, where the field
        // asked for letters and a word is a thing the swipe can delete.
        val view = pad(inputType = textField, isOtp = false)
        assertTrue(view.allowsWordDelete)
    }

    @Test
    fun `the paste key appears only for a code field, and says it in the app language`() {
        val otp = pad(inputType = textField, isOtp = true)
        val paste = otp.laidOutKeys().firstOrNull { it.code == "PASTE_OTP" }
        assertNotNull("a code field should offer to paste the code", paste)
        assertEquals(context.getString(com.goviet.R.string.tpad_paste), paste!!.label)

        val plain = pad(inputType = numberField, isOtp = false)
        assertEquals(0, plain.laidOutKeys().count { it.code == "PASTE_OTP" })
    }

    @Test
    fun `the space key label comes from resources too`() {
        val view = pad(inputType = textField)
        val space = key(view, "SPACE")
        assertEquals(context.getString(com.goviet.R.string.key_space), space.label)
    }
}
