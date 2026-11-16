package com.goviet.keyboard.engine

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.TextAttribute
import com.goviet.keyboard.VietnameseInputMethodService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Robustness tests for the spanless (no underline/composing span) preedit path.
 *
 * Focus areas:
 * - delivery verification must distinguish "missing" from "unreadable"
 * - echo-caret cache must not leak across fields
 * - unreadable-but-correct editors must not be corrupted by fallback rewrites
 */
@RunWith(RobolectricTestRunner::class)
class SpanlessDeliveryRobustnessTest {

    private lateinit var engine: VietnameseComposer
    private lateinit var controller: ImeInputConnectionController
    private lateinit var view: View

    @Before
    fun setUp() {
        engine = VietnameseComposer()
        engine.vietnameseModeEnabled = true
        val service = Robolectric.buildService(VietnameseInputMethodService::class.java).get()
        controller = ImeInputConnectionController(service, engine)
        view = View(service)
    }

    private fun livePreedit(word: String, start: Int) {
        val raw = engine.adoptRoundTrip(word)
        assertNotNull("\"$word\" must round-trip to Telex raw", raw)
        engine.setComposingRaw(raw!!)
        assertEquals(word, engine.toDisplayString())
        controller.composingStartInEditor = start
        controller.composingCursorIndex = engine.composingRawLength()
        controller.lastSetComposingText = word
    }

    private fun setRaw(raw: String, composingStart: Int = 0) {
        engine.setComposingRaw(raw)
        controller.composingCursorIndex = raw.length
        controller.composingStartInEditor = composingStart
    }

    private fun updateSelection(start: Int, end: Int) =
        controller.onUpdateSelection(-1, -1, start, end, -1, -1)

    @Test
    fun resetDeliveryMode_clearsExpectedCaretCacheAcrossFields() {
        livePreedit("thấy", start = 4)
        val editor = BaseInputConnection(view, true)
        controller.writePreedit(editor, "thấy")

        // Simulate switching to a new field: stale echo position must not survive.
        controller.resetDeliveryMode()

        // Put controller in a state where adoption would be observable if the
        // callback is (incorrectly) treated as our own echo.
        livePreedit("thấy", start = 4)
        controller.composingCursorIndex = 0
        updateSelection(8, 8)

        // With stale expected caret cleared, callback at 8 is a tap/echo inside
        // range and adoption runs, restoring cursor to end of "thấy".
        assertEquals(engine.composingRawLength(), controller.composingCursorIndex)
        assertEquals("thấy", controller.lastSetComposingText)
    }

    @Test
    fun unreadableEditor_replaceTextLands_rewriteIsNotCorrupted() {
        val editor = UnreadableButApplyingEditor(view)
        engine.composeAsVietnamese = true
        editor.replaceText(0, 0, "ba", 1, null)
        editor.setSelection(2, 2)

        // Start composing "ba" at range [0,2), then apply tone: "bas" -> "bá"
        setRaw("ba", composingStart = 0)
        controller.lastSetComposingText = "ba"
        controller.writePreedit(editor, "ba") // fast path, establishes state

        setRaw("bas", composingStart = 0)
        controller.writePreedit(editor, engine.toDisplayString())

        assertEquals("bá", editor.text())
        // Unreadable verification must not force a false TRANSIENT verdict.
        assertFalse(controller.deliveryMode == ImeInputConnectionController.DeliveryMode.TRANSIENT)
    }

    @Test
    fun unreadableEditor_unknownModeSuffixDeleteUsesSafeFallbackWithoutLockingTransient() {
        val editor = UnreadableSuffixDeleteEditor(view)
        engine.composeAsVietnamese = true
        // No managed range: rewrite must go through diff path, where UNKNOWN +
        // unreadable delivery should not lock the field as TRANSIENT.
        editor.delivered.append("ban")
        setRaw("bas", composingStart = -1)
        controller.lastSetComposingText = "ban"

        controller.writePreedit(editor, engine.toDisplayString())

        assertEquals("bá", editor.delivered.toString())
        assertEquals(ImeInputConnectionController.DeliveryMode.UNKNOWN, controller.deliveryMode)
    }

    @Test
    fun retainingEditor_fastPathStaysRetainsAfterVerifiedRewrite() {
        val editor = BaseInputConnection(view, true)
        engine.composeAsVietnamese = true
        setRaw("ba", composingStart = 0)
        controller.lastSetComposingText = "ba"
        controller.writePreedit(editor, "ba")

        setRaw("bas", composingStart = 0)
        controller.writePreedit(editor, engine.toDisplayString())
        assertEquals("bá", editor.getTextBeforeCursor(8, 0).toString())
        assertEquals(ImeInputConnectionController.DeliveryMode.RETAINS, controller.deliveryMode)

        // Further plain extension should keep working correctly.
        setRaw("basc", composingStart = 0)
        controller.writePreedit(editor, engine.toDisplayString())
        assertEquals("bác", editor.getTextBeforeCursor(8, 0).toString())
        assertEquals(ImeInputConnectionController.DeliveryMode.RETAINS, controller.deliveryMode)
    }

    @Test
    fun transientTerminal_longerPlainTypingNoDuplication() {
        val terminal = TransientTerminalEditor(view)
        engine.composeAsVietnamese = true
        val keys = listOf("o", "op", "ope", "open", "openc", "openco", "opencod", "opencode")
        for (k in keys) {
            setRaw(k, composingStart = -1)
            controller.writePreedit(terminal, engine.toDisplayString())
        }
        assertEquals("opencode", terminal.delivered.toString())
    }
}

/**
 * An editor that applies replaceText/commitText/delete/selection correctly but
 * never exposes text reads back to the IME (getTextBeforeCursor returns null,
 * getExtractedText null). This models WebView/IC implementations where delivery
 * verification is unreadable.
 */
private class UnreadableButApplyingEditor(view: View) : BaseInputConnection(view, true) {
    fun text(): String = editable?.toString() ?: ""

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = null
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = null
    override fun getExtractedText(request: ExtractedTextRequest, flags: Int): ExtractedText? = null
}

/**
 * Models an editor where text reads are unreadable and text-command deletes do
 * not affect the app buffer, but key events do. Used to exercise the UNKNOWN +
 * UNREADABLE fallback in suffix deletion without taking the replaceText path.
 */
private class UnreadableSuffixDeleteEditor(view: View) : BaseInputConnection(view, true) {
    val delivered = StringBuilder()

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        delivered.append(text)
        editable?.clear()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = true

    override fun replaceText(
        start: Int,
        end: Int,
        text: CharSequence,
        newCursorPosition: Int,
        textAttribute: TextAttribute?
    ): Boolean = true

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL) {
            if (delivered.isNotEmpty()) delivered.deleteCharAt(delivered.length - 1)
        }
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = null
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = null
    override fun getExtractedText(request: ExtractedTextRequest, flags: Int): ExtractedText? = null
}
