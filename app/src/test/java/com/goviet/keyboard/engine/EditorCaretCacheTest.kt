package com.goviet.keyboard.engine

import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import com.goviet.keyboard.VietnameseInputMethodService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The caret side of heavy-web-page robustness: a busy editor answers position
 * queries slowly or not at all, so the controller must ask as rarely as possible
 * and never mistake the echo of its own write for a user tap.
 *
 * The rule pinned here is the expected-caret fast path. Every write records where
 * it left the editor caret; a selection callback that matches it is recognised
 * WITHOUT a round-trip and without an adoption, because the engine cursor is
 * already where that write put it. A callback that does not match keeps the old,
 * verified path: a live read, then adoption.
 *
 * The other two savings are pinned too: an unknown caret skips the word scan
 * before it reads any text, and a transient field never scans for a word at all.
 */
@RunWith(RobolectricTestRunner::class)
class EditorCaretCacheTest {

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

    /** Live preedit [word] owned at editor position [start], caret at the end. */
    private fun livePreedit(word: String, start: Int) {
        val raw = engine.adoptRoundTrip(word)
        assertNotNull("\"$word\" must round-trip to Telex raw", raw)
        engine.setComposingRaw(raw!!)
        assertEquals(word, engine.toDisplayString())
        controller.composingStartInEditor = start
        controller.composingCursorIndex = engine.composingRawLength()
        controller.lastSetComposingText = word
    }

    private fun updateSelection(start: Int, end: Int) =
        controller.onUpdateSelection(-1, -1, start, end, -1, -1)

    @Test
    fun theEchoOfOurOwnWriteIsRecognisedWithoutAReadOrAnAdoption() {
        livePreedit("thấy", start = 4)
        val editor = CountingEditor(view)
        // Writing the same display back takes the fast path, which records the
        // caret it produced (4 + 4 = 8) and touches nothing.
        controller.writePreedit(editor, "thấy")
        // Pretend the engine cursor is somewhere else, so an adoption would show.
        controller.composingCursorIndex = 0

        updateSelection(8, 8)

        // The echo matched the recorded caret, so nothing was adopted.
        assertEquals(0, controller.composingCursorIndex)
        assertEquals("thấy", controller.lastSetComposingText)
    }

    @Test
    fun aCaretThatDiffersFromOurWriteIsAdopted() {
        livePreedit("thấy", start = 4)
        val editor = CountingEditor(view)
        controller.writePreedit(editor, "thấy")
        controller.composingCursorIndex = 0

        // A tap after "th": two display characters into the word.
        updateSelection(6, 6)

        val expected = controller.rawIndexOfDisplay(
            engine.composingRaw(), "thấy", 2, engine.composeAsVietnamese
        )
        assertEquals(expected, controller.composingCursorIndex)
        assertEquals("thấy", controller.lastSetComposingText)
    }

    @Test
    fun anUnknownCaretSkipsTheWordScanWithoutReadingText() {
        val editor = CountingEditor(view, "ban")

        val word = controller.findWordAroundCursor(editor, -1)

        assertNull(word)
        assertEquals(0, editor.textBeforeCursorCalls)
        assertEquals(0, editor.textAfterCursorCalls)
    }

    @Test
    fun aTransientFieldNeverScansForAWordToAdopt() {
        val terminal = TransientTerminalEditor(view)
        // Classify the field through the real path: a tone change needs a removal,
        // and a transient terminal ignores the text command.
        write(terminal, "ban")
        write(terminal, "bans")
        assertEquals(ImeInputConnectionController.DeliveryMode.TRANSIENT, controller.deliveryMode)
        controller.clearState()
        val readsBefore = terminal.textBeforeCursorCalls
        val extractedBefore = terminal.extractedTextCalls

        controller.adoptPrefixAtCaret(terminal)

        // No caret read and no text read: the whole scan is skipped.
        assertEquals(readsBefore, terminal.textBeforeCursorCalls)
        assertEquals(extractedBefore, terminal.extractedTextCalls)
    }

    private fun write(ic: InputConnection, raw: String) {
        engine.setComposingRaw(raw)
        controller.composingCursorIndex = raw.length
        controller.composingStartInEditor = 0
        controller.writePreedit(ic, engine.toDisplayString())
    }
}

/**
 * A normal editor whose text reads are counted, so a test can answer "how many
 * blocking round-trips did that cost?" — the question a heavy WebView makes
 * decisive.
 */
private class CountingEditor(view: View, private val text: String = "") : BaseInputConnection(view, true) {

    var textBeforeCursorCalls = 0
    var textAfterCursorCalls = 0

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
        textBeforeCursorCalls++
        return text
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
        textAfterCursorCalls++
        return ""
    }
}
