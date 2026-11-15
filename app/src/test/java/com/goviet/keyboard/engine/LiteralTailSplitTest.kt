package com.goviet.keyboard.engine

import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.TextAttribute
import com.goviet.keyboard.VietnameseInputMethodService
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * A reverted Telex key stays in the raw buffer and keeps shadowing the next key
 * until the caret is lifted out of its way.
 *
 * The user types "e r r": the second "r" reverts the hook and locks the first
 * "r" as a literal tail, so the word renders "er" (raw "err", tail "r"). The
 * kernel re-segments the WHOLE raw on every keystroke, so a plain insert at raw
 * index 1 produces "esrr", which re-segments straight back to "er" — the "s" is
 * swallowed. Tapping between the "e" and the "r" and typing "s" must instead
 * give "ér", and a second "s" must give "esr".
 *
 * These tests drive the real write path ([writePreedit]) against a stateful
 * editor so the assertions are on the text the user actually sees, not on an
 * intermediate buffer.
 */
@RunWith(RobolectricTestRunner::class)
class LiteralTailSplitTest {

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

    /** One keystroke through the live path: buffer first, then the editor write. */
    private fun typeKey(editor: StatefulEditor, key: Char) {
        controller.applyKeyToComposingBuffer(key)
        controller.updateComposingUI(editor, 0, engine.toDisplayString())
    }

    @Test
    fun aKeyTypedBeforeTheRevertedTailEditsTheSyllableNotTheTail() {
        // "e r r" renders "er": a hook was applied and then reverted, leaving the
        // first "r" locked as a literal tail.
        engine.setComposingRaw("err")
        assertEquals("er", engine.toDisplayString())
        controller.composingStartInEditor = 0
        controller.lastSetComposingText = "er"
        // The user taps between the "e" and the "r".
        controller.composingCursorIndex = 1
        val editor = StatefulEditor(view, "er")

        // Acute tone on the "e" only; the tail must not swallow the key.
        typeKey(editor, 's')
        assertEquals("ér", editor.text.toString())
        assertEquals("é", engine.toDisplayString())
        assertEquals("é", controller.lastSetComposingText)
        assertEquals(2, controller.composingCursorIndex)

        // Second "s" reverts the tone: the syllable becomes "es" and the locked
        // "r" is still sitting in the editor right after it.
        typeKey(editor, 's')
        assertEquals("esr", editor.text.toString())
        assertEquals("es", engine.toDisplayString())
        assertEquals("es", controller.lastSetComposingText)
        assertEquals(3, controller.composingCursorIndex)
    }

    @Test
    fun aCaretAtTheEndOfThePreeditNeverSplits() {
        engine.setComposingRaw("err")
        controller.composingStartInEditor = 0
        controller.lastSetComposingText = "er"
        controller.composingCursorIndex = engine.composingRawLength()
        val editor = StatefulEditor(view, "er")

        // No split at the end: the locked tail stays in the buffer and the key
        // is appended to it literally, exactly as before the fix.
        typeKey(editor, 's')
        assertEquals("ers", editor.text.toString())
        assertEquals("errs", engine.composingRaw().toString())
    }
}

/**
 * A minimal editor that really applies text commands, so a test can assert on
 * the visible text after a preedit write instead of on the IME's own bookkeeping.
 */
private class StatefulEditor(view: View, initialText: String) : BaseInputConnection(view, true) {

    val text = StringBuilder(initialText)
    private var caret = initialText.length

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
        text.substring(maxOf(0, caret - n), caret)

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence =
        text.substring(caret, minOf(text.length, caret + n))

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        this.text.insert(caret, text)
        caret += text.length
        return true
    }

    override fun replaceText(
        start: Int,
        end: Int,
        text: CharSequence,
        newCursorPosition: Int,
        textAttribute: TextAttribute?
    ): Boolean {
        this.text.replace(start, end, text.toString())
        caret = start + text.length
        return true
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        caret = start
        return true
    }
}
