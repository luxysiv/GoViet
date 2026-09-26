package com.goviet.keyboard.engine

import com.goviet.keyboard.VietnameseInputMethodService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * What the controller actually DOES with each selection verdict, as opposed to
 * [SelectionProtocolTest] which only pins the rule.
 *
 * These are the three failures that were real on the device:
 *
 *  - Zalo reports -1 around a focus change. Read as a position, that ended the
 *    composition and dropped the preedit, so the next keystroke committed a
 *    half-typed word.
 *  - The echo of our own write arrives a frame later. If it is not recognised,
 *    the preedit is dropped and rewritten — the flicker.
 *  - A real move must drop the buffer: it cannot be written back where the
 *    editor now is.
 *
 * No editor is involved: the reactions under test are engine state, and
 * currentInputConnection is null in this setup, so the "re-anchor at the caret"
 * step is skipped exactly as it would be with no focused editor.
 */
@RunWith(RobolectricTestRunner::class)
class SelectionCallbackTest {

    private lateinit var engine: VietnameseComposer
    private lateinit var controller: ImeInputConnectionController

    @Before
    fun setUp() {
        engine = VietnameseComposer()
        engine.vietnameseModeEnabled = true
        val service = Robolectric.buildService(VietnameseInputMethodService::class.java).get()
        controller = ImeInputConnectionController(service, engine)
    }

    private fun updateSelection(start: Int, end: Int) =
        controller.onUpdateSelection(-1, -1, start, end, -1, -1)

    /** Live preedit [word] owned at editor position [start]. */
    private fun livePreedit(word: String, start: Int = 0, caretIndex: Int = word.length) {
        val raw = engine.adoptRoundTrip(word)
        assertNotNull("\"$word\" must round-trip to Telex raw", raw)
        engine.setComposingRaw(raw!!)
        assertEquals(word, engine.toDisplayString())
        controller.composingStartInEditor = start
        controller.composingCursorIndex = caretIndex
        controller.lastSetComposingText = word
    }

    @Test
    fun focusChangeReportingMinusOneKeepsTheLivePreedit() {
        livePreedit("ba")
        controller.composingCursorIndex = engine.composingRawLength()

        // Zalo around a focus change: no position, so no decision to make.
        updateSelection(-1, -1)

        assertEquals("ba", controller.lastSetComposingText)
        assertEquals("ba", engine.toDisplayString())
        assertEquals(0, controller.composingStartInEditor)
        assertTrue(engine.isComposing())
        assertFalse(controller.userMovedCursor)

        // And the next keystroke therefore extends the SAME word, instead of
        // committing "ba" and starting over — the visible symptom.
        assertFalse(controller.applyKeyToComposingBuffer('s'))
        assertEquals("bá", engine.toDisplayString())
        assertEquals("ba", controller.lastSetComposingText)
    }

    @Test
    fun echoOfOurOwnWriteKeepsThePreeditAndAdoptsTheCaret() {
        // "thấy" at [4, 8): our replaceText landed the caret at 8, which is
        // exactly the echo the editor sends back a frame later.
        livePreedit("thấy", start = 4)

        updateSelection(8, 8)

        assertEquals("thấy", controller.lastSetComposingText)
        assertEquals("thấy", engine.toDisplayString())
        assertEquals(4, controller.composingStartInEditor)
        assertEquals(engine.composingRawLength(), controller.composingCursorIndex)
        assertFalse(controller.userMovedCursor)
        assertFalse(controller.userSelectedText)
    }

    @Test
    fun tappingInsideThePreeditAdoptsTheCaretWithoutRewriting() {
        livePreedit("thấy", start = 4)

        // Caret after "t" of the preedit: a real user tap, which must keep the
        // word and move the engine cursor, not drop and re-adopt the buffer.
        updateSelection(5, 5)

        assertEquals("thấy", controller.lastSetComposingText)
        assertEquals("thấy", engine.toDisplayString())
        assertEquals(4, controller.composingStartInEditor)
        assertEquals(1, controller.composingCursorIndex)
        assertFalse(controller.userMovedCursor)
    }

    @Test
    fun aRealMoveDropsTheBufferBecauseItCannotBeWrittenBack() {
        livePreedit("thấy", start = 4)

        updateSelection(2, 2)

        assertNull(controller.lastSetComposingText)
        assertEquals(-1, controller.composingStartInEditor)
        assertFalse(engine.isComposing())
        assertTrue(controller.userMovedCursor)
        assertFalse(controller.userSelectedText)
    }

    @Test
    fun aSelectionInsideThePreeditIsNotTreatedAsACaret() {
        livePreedit("thấy", start = 4)

        updateSelection(5, 7)

        assertTrue(controller.userSelectedText)
        assertTrue(controller.userMovedCursor)
        assertNull(controller.lastSetComposingText)
        assertFalse(engine.isComposing())
    }

    @Test
    fun selectionReportedByAFocusChangeLeavesCommittedTextAlone() {
        // No preedit: a -1 around a focus change must not invent a user move,
        // because the next keystroke re-anchors on committed text anyway.
        assertFalse(engine.isComposing())
        updateSelection(-1, -1)

        assertFalse(controller.userMovedCursor)
        assertFalse(controller.userSelectedText)
        assertNull(controller.lastSetComposingText)
    }
}
