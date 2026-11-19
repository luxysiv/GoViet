package com.goviet.keyboard.engine

import android.view.View
import android.view.inputmethod.InputConnection
import com.goviet.keyboard.VietnameseInputMethodService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Regression: a tone key typed inside an ALREADY-TRANSFORMED word must retone
 * that word, never be spliced into its raw buffer.
 *
 * The user's case: "đồng" (raw with an early tone key, e.g. "ddoongsf") with
 * the caret between "n" and "g", then 's' must give "đống". Splicing 's' into
 * that raw produces a second tone key next to the first one, the kernel
 * untoggles it and leaks raw letters as literal text ("đosnosgf").
 */
@RunWith(RobolectricTestRunner::class)
class RetoneMidWordTest {

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

    private fun typeKey(editor: InputConnection, key: Char) {
        controller.applyKeyToComposingBuffer(key, editor)
        controller.updateComposingUI(editor, 0, controller.compileComposingText())
    }

    private fun livePreedit(editor: ComposingEditor, raw: String, display: String) {
        engine.setComposingRaw(raw)
        assertEquals(display, engine.toDisplayString())
        controller.composingStartInEditor = 0
        controller.composingCursorIndex = raw.length
        controller.lastSetComposingText = display
        controller.writePreedit(editor, display)
    }

    @Test
    fun toneKeyBetweenNAndGRetonesDongWithoutRawLeakage() {
        val editor = ComposingEditor(view)
        livePreedit(editor, "ddoongsf", "đồng")

        // Tap between "n" and "g": the editor reports the caret inside our span.
        editor.setSelection(3, 3)
        controller.onUpdateSelection(4, 4, 3, 3, 0, 4)
        assertEquals(5, controller.composingCursorIndex)

        typeKey(editor, 's')

        assertEquals("đống", editor.text.toString())
        assertFalse("raw key leaked: ${editor.text}", editor.text.toString().contains('f'))
        assertFalse("raw key leaked: ${editor.text}", editor.text.toString().contains("osn"))
    }

    @Test
    fun typingDdoongsfStillRendersDongCleanly() {
        val editor = ComposingEditor(view)
        for (key in "ddoongsf") typeKey(editor, key)

        assertEquals("đồng", editor.text.toString())
        assertEquals(0, engine.literalTailLength())
    }

    @Test
    fun pressingTheSameToneTwiceKeepsTheEngineLiteralKey() {
        val editor = ComposingEditor(view)
        livePreedit(editor, "ddoongsf", "đồng")

        // 'f' is already the syllable's tone: the engine untoggles it and locks
        // the repeated key as literal text ("ddoongsff" -> "đôngf"), exactly as
        // process("aff") -> "af". A clean retone would have dropped the 'f'.
        typeKey(editor, 'f')

        assertEquals("đôngf", editor.text.toString())
    }

    @Test
    fun clearToneKeyConsumesItselfInsteadOfGoingLiteral() {
        val editor = ComposingEditor(view)
        livePreedit(editor, "ddoongsf", "đồng")

        // 'z' clears the tone and is consumed ("ddoongfz" -> "đông"), whereas a
        // naive retone would have appended it as a literal ("đôngz").
        typeKey(editor, 'z')

        assertEquals("đông", editor.text.toString())
    }

    @Test
    fun toneKeyAppendedAtTheEndKeepsTheCaretAtTheEnd() {
        val editor = ComposingEditor(view)
        // 'r' is a tone key, but "t" has no nucleus to retone, so the key is
        // appended literally and merely extends the onset into "tr".
        typeKey(editor, 't')
        assertEquals("t", editor.text.toString())
        assertEquals(1, controller.composingCursorIndex)

        typeKey(editor, 'r')
        assertEquals("tr", editor.text.toString())
        assertEquals(2, controller.composingCursorIndex)
    }

    @Test
    fun toneKeyAfterBareROnsetIsAppendedNotSwallowed() {
        val editor = ComposingEditor(view)
        typeKey(editor, 'r')
        assertEquals("r", editor.text.toString())

        // 'r' is itself a Telex tone letter, so the old retone path stripped it
        // from the bare onset and kept only the newly typed 's'.
        typeKey(editor, 's')
        assertEquals("rs", editor.text.toString())
        assertEquals("rs", engine.composingRaw().toString())
        assertEquals(2, controller.composingCursorIndex)
    }

    @Test
    fun toneKeyAfterBareSOnsetIsAppendedNotSwallowed() {
        val editor = ComposingEditor(view)
        typeKey(editor, 's')
        assertEquals("s", editor.text.toString())

        typeKey(editor, 'x')
        assertEquals("sx", editor.text.toString())
        assertEquals("sx", engine.composingRaw().toString())
        assertEquals(2, controller.composingCursorIndex)
    }

    @Test
    fun toneKeyAfterCompoundOnsetEndingInRKeepsWholeOnset() {
        val editor = ComposingEditor(view)
        typeKey(editor, 't')
        typeKey(editor, 'r')
        assertEquals("tr", editor.text.toString())

        typeKey(editor, 's')
        assertEquals("trs", editor.text.toString())
        assertEquals("trs", engine.composingRaw().toString())
        assertEquals(3, controller.composingCursorIndex)
    }

    @Test
    fun caretInsideVowelMapsAcrossToneShapeChange() {
        val editor = ComposingEditor(view)
        livePreedit(editor, "ddoongsf", "đồng")

        // No raw prefix renders the toned vowel "ồ" exactly; the tone-insensitive
        // pass must map it to the prefix that renders plain "ô" (raw index 4).
        editor.setSelection(2, 2)
        controller.onUpdateSelection(4, 4, 2, 2, 0, 4)
        assertEquals(4, controller.composingCursorIndex)

        typeKey(editor, 's')
        assertEquals("đống", editor.text.toString())
    }
}
