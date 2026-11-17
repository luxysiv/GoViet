package com.goviet.keyboard.engine

import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.TextAttribute
import com.goviet.keyboard.VietnameseInputMethodService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The normal field now uses Android's composing-span contract. No text read-back,
 * no replaceText verification, no key-event delete: the editor diffs the region
 * itself, which is what rich web/chat fields (AI Studio, Docs, Gemini) expect.
 */
@RunWith(RobolectricTestRunner::class)
class ComposingSpanDeliveryTest {

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

    private fun typeRaw(editor: InputConnection, raw: String) {
        engine.setComposingRaw(raw)
        controller.composingCursorIndex = raw.length
        controller.writePreedit(editor, engine.toDisplayString())
    }

    @Test
    fun theemBecomesThemThroughSetComposingTextOnly() {
        val editor = ComposingEditor(view)
        for (raw in listOf("t", "th", "the", "thee", "theem")) typeRaw(editor, raw)

        assertEquals("thêm", editor.text.toString())
        assertEquals(5, editor.setComposingTextCalls)
        assertEquals(0, editor.replaceTextCalls)
        assertEquals(0, editor.keyEvents)
    }

    @Test
    fun spaceFinishesTheSpanAndCommitsOnlyTheSeparator() {
        val editor = ComposingEditor(view)
        for (raw in listOf("t", "th", "the", "thee", "theem")) typeRaw(editor, raw)

        controller.commitAndResetTo(editor, " ", alreadyBatched = true)

        assertEquals("thêm ", editor.text.toString())
        assertEquals(1, editor.finishComposingTextCalls)
        assertEquals(1, editor.commitTextCalls)
        assertFalse(engine.isComposing())
    }

    @Test
    fun aCaretInsideThePreeditIsPlacedWithAnAbsoluteSelection() {
        val editor = ComposingEditor(view)
        engine.setComposingRaw("ban")
        controller.composingCursorIndex = 1
        controller.composingStartInEditor = 2
        controller.lastSetComposingText = "ban"

        controller.writePreedit(editor, "ban")

        assertEquals(3, editor.lastSelection)
    }

    @Test
    fun candidatesRangeKeepsTheEchoOfOurOwnWrite() {
        val editor = ComposingEditor(view)
        typeRaw(editor, "thấy")
        controller.composingCursorIndex = 0

        controller.onUpdateSelection(-1, -1, 8, 8, 4, 8)

        assertEquals("thấy", controller.lastSetComposingText)
        assertEquals(4, controller.composingStartInEditor)
        assertEquals(editor.composingRawLength(), controller.composingCursorIndex)
        assertEquals(engine.composingRawLength(), controller.composingCursorIndex)
    }

    @Test
    fun backspaceInsideThePreeditRewritesTheComposingRegion() {
        val editor = ComposingEditor(view)
        engine.setComposingRaw("bas")
        controller.composingCursorIndex = 3
        controller.composingStartInEditor = 0
        controller.lastSetComposingText = "bá"
        editor.setComposingText("bá", 1)
        controller.writePreedit(editor, "bá")

        controller.backspaceHandler.handleBackspace(editor)

        assertEquals("b", editor.text.toString())
        assertEquals(0, editor.replaceTextCalls)
        assertEquals(0, editor.keyEvents)
    }
}

/** Models a real Android editor: text commands work, composing spans are reported. */
internal class ComposingEditor(view: View) : BaseInputConnection(view, true) {

    val text = StringBuilder()
    private var caret = 0
    private var composingStart = -1
    private var composingEnd = -1

    var setComposingTextCalls = 0
    var finishComposingTextCalls = 0
    var commitTextCalls = 0
    var replaceTextCalls = 0
    var keyEvents = 0
    var lastSelection = -1

    fun composingRawLength(): Int = maxOf(0, composingEnd - composingStart)

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
        text.substring(maxOf(0, caret - n), caret)

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence =
        text.substring(caret, minOf(text.length, caret + n))

    override fun getExtractedText(request: ExtractedTextRequest, flags: Int): ExtractedText =
        ExtractedText().apply {
            this.text = this@ComposingEditor.text
            selectionStart = caret
            selectionEnd = caret
            startOffset = 0
        }

    override fun setComposingText(value: CharSequence, newCursorPosition: Int): Boolean {
        setComposingTextCalls++
        val start = if (composingStart >= 0 && composingEnd >= composingStart) composingStart else caret
        val end = if (composingStart >= 0 && composingEnd >= composingStart) composingEnd else caret
        text.replace(start, end, value.toString())
        caret = start + value.length + (newCursorPosition - 1)
        composingStart = start
        composingEnd = start + value.length
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        composingStart = start
        composingEnd = end
        return true
    }

    override fun finishComposingText(): Boolean {
        finishComposingTextCalls++
        composingStart = -1
        composingEnd = -1
        return true
    }

    override fun commitText(value: CharSequence, newCursorPosition: Int): Boolean {
        commitTextCalls++
        // Android commits an open composing span implicitly; clear our model's
        // span WITHOUT bumping the explicit finishComposingText counter.
        composingStart = -1
        composingEnd = -1
        text.insert(caret, value)
        caret += value.length + (newCursorPosition - 1)
        return true
    }

    override fun replaceText(
        start: Int,
        end: Int,
        value: CharSequence,
        newCursorPosition: Int,
        textAttribute: TextAttribute?
    ): Boolean {
        replaceTextCalls++
        composingStart = -1
        composingEnd = -1
        text.replace(start, end, value.toString())
        caret = start + value.length + (newCursorPosition - 1)
        return true
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        lastSelection = start
        caret = start
        return true
    }

    override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
        keyEvents++
        return true
    }
}
