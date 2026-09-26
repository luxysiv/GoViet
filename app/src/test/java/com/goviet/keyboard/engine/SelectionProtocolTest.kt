package com.goviet.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The selection protocol, tested as the pure rule it is: five numbers in, one
 * verdict out.
 *
 * The rule is what lets the IME trust geometry instead of bookkeeping, so the
 * cases that must never regress are the ones where the OLD designs guessed:
 *
 *  - an editor reporting -1 (Zalo around a focus change) used to be read as a
 *    position, which ended the composition and dropped the preedit;
 *  - an echo of our own write used to need a predicted-caret ledger to be
 *    recognised, so a missed update showed up as a user move;
 *  - a selection used to be guessed from an extracted-text offset, so a real one
 *    could be missed and the next backspace would delete inside it.
 */
class SelectionProtocolTest {

    // Live preedit "thấy" owned at [4, 8), caret written at 8.
    private val composing = true
    private val preeditStart = 4
    private val preeditLength = 4

    @Test
    fun negativeStartIsNoInformationNotAPosition() {
        assertEquals(SelectionVerdict.NO_INFO, reconcileSelection(-1, -1, composing, preeditStart, preeditLength))
        assertEquals(SelectionVerdict.NO_INFO, reconcileSelection(-1, 12, composing, preeditStart, preeditLength))
    }

    @Test
    fun negativeStartIsNoInformationEvenWithNoPreedit() {
        // Nothing to reconcile either way: must not be able to look like a move.
        assertEquals(SelectionVerdict.NO_INFO, reconcileSelection(-1, -1, composing = false, preeditStart = -1, preeditLength = 0))
    }

    @Test
    fun echoAtTheEndOfTheRangeKeepsThePreedit() {
        // Exactly what our own replaceText produces: the caret lands after the
        // new text, i.e. at preeditEnd.
        assertEquals(SelectionVerdict.KEEP_PREEDIT, reconcileSelection(8, 8, composing, preeditStart, preeditLength))
    }

    @Test
    fun caretAnywhereInsideTheRangeKeepsThePreedit() {
        assertEquals(SelectionVerdict.KEEP_PREEDIT, reconcileSelection(4, 4, composing, preeditStart, preeditLength))
        assertEquals(SelectionVerdict.KEEP_PREEDIT, reconcileSelection(6, 6, composing, preeditStart, preeditLength))
    }

    @Test
    fun caretOutsideTheRangeIsAUserMove() {
        assertEquals(SelectionVerdict.USER_MOVE, reconcileSelection(3, 3, composing, preeditStart, preeditLength))
        assertEquals(SelectionVerdict.USER_MOVE, reconcileSelection(9, 9, composing, preeditStart, preeditLength))
        assertEquals(SelectionVerdict.USER_MOVE, reconcileSelection(0, 0, composing, preeditStart, preeditLength))
    }

    @Test
    fun aCaretInsideTheRangeWithoutACompositionIsStillAUserMove() {
        // No buffer means nothing to keep: the preedit word is committed text now,
        // and the next keystroke must re-resolve it from the editor.
        assertEquals(SelectionVerdict.USER_MOVE, reconcileSelection(6, 6, composing = false, preeditStart, preeditLength))
    }

    @Test
    fun aCaretEqualToTheRangeIsNotKeptWhenNoRangeIsOwned() {
        // preeditStart -1 = we own no range (macro rollback before the range is
        // claimed): the preedit is in the editor but not ours, so a callback
        // about it cannot be trusted as our echo.
        assertEquals(SelectionVerdict.USER_MOVE, reconcileSelection(6, 6, composing, preeditStart = -1, preeditLength = 4))
    }

    @Test
    fun aSelectionIsNeverKeptEvenInsideTheRange() {
        // Selecting part of your own preedit means the range is no longer ours to
        // rewrite; guessing "caret" here would clobber the selection.
        assertEquals(SelectionVerdict.USER_SELECTION, reconcileSelection(6, 7, composing, preeditStart, preeditLength))
        assertEquals(SelectionVerdict.USER_SELECTION, reconcileSelection(4, 8, composing, preeditStart, preeditLength))
    }

    @Test
    fun unknownEndWithKnownStartIsACaretNotASelection() {
        // Otherwise the next backspace deletes inside a selection nobody made.
        assertEquals(SelectionVerdict.KEEP_PREEDIT, reconcileSelection(6, -1, composing, preeditStart, preeditLength))
        assertEquals(SelectionVerdict.USER_MOVE, reconcileSelection(20, -1, composing, preeditStart, preeditLength))
    }

    @Test
    fun anEmptyPreeditRangeKeepsTheCaretItWasGiven() {
        // The mid-word split writes a new syllable as a pure insertion, so the
        // range it owns is empty at the caret: that caret IS the preedit and must
        // not be mistaken for a move.
        assertEquals(SelectionVerdict.KEEP_PREEDIT, reconcileSelection(6, 6, composing, preeditStart = 6, preeditLength = 0))
        assertEquals(SelectionVerdict.USER_MOVE, reconcileSelection(5, 5, composing, preeditStart = 6, preeditLength = 0))
    }

    @Test
    fun everyVerdictIsReachableWithoutAnyTimingInput() {
        // Guards the shape of the API, not a behaviour: the function takes no
        // clock, so no caller can smuggle one in through a callback. All four
        // verdicts are reachable from these five numbers alone.
        val reachable = setOf(
            reconcileSelection(-1, -1, composing, preeditStart, preeditLength),
            reconcileSelection(6, 6, composing, preeditStart, preeditLength),
            reconcileSelection(0, 0, composing, preeditStart, preeditLength),
            reconcileSelection(6, 7, composing, preeditStart, preeditLength)
        )
        assertEquals(SelectionVerdict.entries.toSet(), reachable)
    }
}
