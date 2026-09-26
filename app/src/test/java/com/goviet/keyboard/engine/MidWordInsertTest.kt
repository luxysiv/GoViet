package com.goviet.keyboard.engine

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
 * Invariant locked by these tests: a keystroke transforms ONLY the syllable
 * under the caret and leaves every neighbouring character byte-identical.
 *
 * The canonical failure is the caret in front of the "t" of the live preedit
 * "thấy": typing "as" must render "áthấy". If the new key is glued onto raw
 * index 0 instead of starting its own syllable, the kernel — which models ONE
 * syllable per buffer — pushes every following letter out of the nucleus into
 * the raw suffix, the tone mark is lost and the trailing tone key leaks out as
 * a literal letter, producing "áthaays".
 *
 * Robolectric is used only to instantiate the controller (its constructor takes
 * the IME service); no InputConnection or editor is touched. The assertions are
 * on the state the next editor write is derived from: the tracked range and the
 * display string.
 */
@RunWith(RobolectricTestRunner::class)
class MidWordInsertTest {

    private lateinit var engine: VietnameseComposer
    private lateinit var controller: ImeInputConnectionController

    @Before
    fun setUp() {
        engine = VietnameseComposer()
        engine.vietnameseModeEnabled = true
        val service = Robolectric.buildService(VietnameseInputMethodService::class.java).get()
        controller = ImeInputConnectionController(service, engine)
    }

    /** Live-preedit state for [word] sitting at editor position 0. */
    private fun livePreedit(word: String) {
        val raw = engine.adoptRoundTrip(word)
        assertNotNull("\"$word\" must round-trip to Telex raw", raw)
        engine.setComposingRaw(raw!!)
        assertEquals(word, engine.toDisplayString())
        controller.composingStartInEditor = 0
        controller.composingCursorIndex = 0
        controller.lastSetComposingText = word
    }

    /** Length of the editor range the next preedit write would replace. */
    private fun trackedRangeLength(): Int = controller.lastSetComposingText?.length ?: 0

    @Test
    fun keyInFrontOfLivePreeditStartsNewSyllableAndKeepsTheWord() {
        livePreedit("thấy")

        // Caret before the onset -> a new syllable starts here.
        assertTrue(controller.applyKeyToComposingBuffer('a'))

        // The buffer now holds ONLY the new key, and the range it owns is empty:
        // the write is a pure insertion at the caret, so "thấy" is never part of
        // the replaced text and cannot be rewritten.
        assertEquals(0, controller.composingStartInEditor)
        assertEquals(0, trackedRangeLength())
        assertEquals("a", engine.composingRaw().toString())
        assertEquals(1, controller.composingCursorIndex)
        assertEquals("a", engine.toDisplayString())

        // Second key: tone mark on the new syllable only.
        assertFalse(controller.applyKeyToComposingBuffer('s'))
        assertEquals("á", engine.toDisplayString())
        assertEquals(0, controller.composingStartInEditor)
        assertEquals(0, trackedRangeLength())
    }

    @Test
    fun keyAtEndOfPreeditAppendsToTheSameSyllable() {
        livePreedit("ba")
        controller.composingCursorIndex = engine.composingRawLength()

        assertFalse(controller.applyKeyToComposingBuffer('s'))

        // Same buffer, whole word still owned by the preedit.
        assertEquals("bá", engine.toDisplayString())
        assertEquals("ba", controller.lastSetComposingText)
        assertEquals(2, trackedRangeLength())
    }

    @Test
    fun keyAfterTheVowelStaysInsideTheSameSyllable() {
        livePreedit("can")
        // Pin the raw so a failure shows what the buffer actually holds.
        assertEquals("can", engine.composingRaw().toString())
        // Caret after the nucleus, coda still behind it: "ca|n".
        controller.composingCursorIndex = 2

        assertFalse(controller.applyKeyToComposingBuffer('a'))

        // One buffer, one syllable: the preedit still owns the whole word, so
        // the write replaces [0, 3) and the letters around the caret are safe.
        // "aa" -> "â" must be composed, the coda must survive.
        assertEquals("cân", engine.toDisplayString())
        assertEquals("can", controller.lastSetComposingText)
        assertEquals(3, trackedRangeLength())
        assertEquals(3, controller.composingCursorIndex)
    }

    @Test
    fun keyAfterTheOnsetStaysInsideTheSameBuffer() {
        livePreedit("thấy")
        controller.composingCursorIndex = 2

        assertFalse(controller.applyKeyToComposingBuffer('a'))

        // No second syllable: the preedit still owns the whole word, so the
        // write replaces [0, 4) and nothing outside the word is touched.
        assertEquals("thấy", controller.lastSetComposingText)
        assertEquals(4, trackedRangeLength())
        assertTrue(controller.composingCursorIndex > 2)
    }

    @Test
    fun keyIntoEmptyBufferNeverSplits() {
        assertFalse(controller.applyKeyToComposingBuffer('t'))
        assertEquals("t", engine.composingRaw().toString())
        assertEquals(1, controller.composingCursorIndex)
    }
}
