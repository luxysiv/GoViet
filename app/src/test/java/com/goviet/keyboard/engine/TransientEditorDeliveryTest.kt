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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The preedit must reach the editor exactly once, per character, on every kind
 * of editor.
 *
 * The failure these tests pin is the "nhân chữ" duplication in a browser
 * terminal: the field is a normal text field (NOT TYPE_NULL), so it took the
 * text-command path, but its hidden textarea is forwarded to the app and cleared
 * after every keystroke. The old write deleted the old preedit with
 * deleteSurroundingText — a no-op on the emptied field — and then committed the
 * WHOLE word again, so the app received "h", then "he", then "hel" and showed
 * "hheell".
 *
 * The delivery rule under test is the fix: a pure extension commits only its
 * tail, and when something inside the word changes the removal travels as the
 * kind of command that editor actually honours (a key event for a transient
 * field, a text command for one that keeps its text).
 *
 * No real editor is involved; [TransientTerminalEditor] and the plain
 * [BaseInputConnection] stand in for the two behaviours.
 */
@RunWith(RobolectricTestRunner::class)
class TransientEditorDeliveryTest {

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

    /** Writes [raw]'s display through the controller, as a keystroke would. */
    private fun write(ic: InputConnection, raw: String) {
        engine.setComposingRaw(raw)
        controller.composingCursorIndex = raw.length
        controller.composingStartInEditor = 0
        controller.writePreedit(ic, engine.toDisplayString())
    }

    // ── A browser terminal: text commands do not reach it, only key events do ──

    @Test
    fun transientEditor_pureExtension_deliversEachCharacterOnce() {
        val terminal = TransientTerminalEditor(view)

        write(terminal, "b")
        write(terminal, "ba")
        write(terminal, "ban")

        // The whole point: "ban", never "bbaann".
        assertEquals("ban", terminal.delivered.toString())
        // And no write needed replaceText at all — the duplication used to come
        // from re-committing the full word after a dead delete.
        assertEquals(0, terminal.replaceTextCalls)
    }

    @Test
    fun transientEditor_toneChange_retractsThroughTheAppDeleteKey() {
        val terminal = TransientTerminalEditor(view)

        write(terminal, "b")
        write(terminal, "ba")
        write(terminal, "ban")
        // "s" turns "ban" into "bán": the changed suffix "an" must leave and "án"
        // must arrive, so the app ends with exactly "bán".
        write(terminal, "bans")

        assertEquals("bán", terminal.delivered.toString())
        // replaceText claimed success and was rejected: the field is marked
        // transient and the retraction went through the delete key instead.
        assertEquals(1, terminal.replaceTextCalls)
        assertEquals(ImeInputConnectionController.DeliveryMode.TRANSIENT, controller.deliveryMode)
        assertTrue(controller.usesKeyEventDeletes())
    }

    @Test
    fun transientEditor_shrinkingDisplay_deletesTheTail() {
        val terminal = TransientTerminalEditor(view)

        write(terminal, "b")
        write(terminal, "ba")
        write(terminal, "ban")
        // A display-level backspace resync writes the shorter word.
        write(terminal, "ba")

        assertEquals("ba", terminal.delivered.toString())
    }

    // ── A normal editor: text commands land, so replaceText stays atomic ──

    @Test
    fun retainingEditor_usesTextCommandsAndKeepsTheAtomicReplace() {
        val editor = BaseInputConnection(view, true)

        write(editor, "b")
        write(editor, "ba")
        write(editor, "ban")
        write(editor, "bans")

        assertEquals("bán", editor.getTextBeforeCursor(8, 0).toString())
        assertEquals(ImeInputConnectionController.DeliveryMode.RETAINS, controller.deliveryMode)
        assertFalse(controller.usesKeyEventDeletes())
    }

    @Test
    fun newFieldForgetsThePreviousVerdict() {
        val terminal = TransientTerminalEditor(view)
        write(terminal, "ban")
        write(terminal, "bans")
        assertEquals(ImeInputConnectionController.DeliveryMode.TRANSIENT, controller.deliveryMode)

        controller.resetDeliveryMode()

        assertEquals(ImeInputConnectionController.DeliveryMode.UNKNOWN, controller.deliveryMode)
    }
}

/**
 * A browser terminal's input field: a normal text field that the app drains and
 * clears after every keystroke. Text commands (`commitText` reaches the app but
 * is then wiped from the field, `deleteSurroundingText` and `replaceText` do
 * nothing) behave nothing like a real editor; only key events reach the app's
 * own buffer, which is what [delivered] models.
 */
internal class TransientTerminalEditor(view: View) : BaseInputConnection(view, true) {

    /** The app-side text, i.e. what the terminal has actually forwarded. */
    val delivered = StringBuilder()

    var replaceTextCalls = 0

    /** Blocking reads, counted so a test can prove a path makes none. */
    var textBeforeCursorCalls = 0
    var extractedTextCalls = 0

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        delivered.append(text)
        // Forwarded and cleared: nothing is left for the next write to replace.
        editable?.clear()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        // A text command never reaches the terminal's own buffer.
        return true
    }

    override fun replaceText(
        start: Int,
        end: Int,
        text: CharSequence,
        newCursorPosition: Int,
        textAttribute: TextAttribute?
    ): Boolean {
        // Claims success, changes nothing — the Chromium WebView bug.
        replaceTextCalls++
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL) {
            if (delivered.isNotEmpty()) delivered.deleteCharAt(delivered.length - 1)
        }
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
        textBeforeCursorCalls++
        return ""
    }

    override fun getExtractedText(request: ExtractedTextRequest, flags: Int): ExtractedText? {
        extractedTextCalls++
        return null
    }
}
