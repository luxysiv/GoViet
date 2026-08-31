package com.goviet.keyboard.engine

import android.view.KeyEvent
import android.view.inputmethod.InputConnection

/**
 * Single Source of Truth for all Backspace, Delete, and Deletion-related operations.
 *
 * Responsibilities:
 * 1. Active selection deletion
 * 2. Active composing session step-by-step undo via IME State Stack
 * 3. Macro expansion rollback (e.g. restoring 'vn' after typing 'vn ')
 * 4. Word adoption & preceding Vietnamese syllable undo
 * 5. Unicode Grapheme Cluster deletion (proper handling of emojis, accents, surrogate pairs)
 * 6. Word-level backward deletion (Ctrl+Backspace / Swipe delete)
 */
class BackspaceHandler(
    private val controller: ImeInputConnectionController
) {

    /**
     * Executes the primary Backspace action according to the strict priority chain:
     * 1. Active Selection -> Delete Selection
     * 2. Active Composing Session -> Step Undo via State Stack
     * 3. Macro Rollback -> Restore original abbreviation trigger
     * 4. Context Word Adoption -> Adopt preceding syllable & undo last step
     * 5. Raw Editor Content -> Delete single Unicode grapheme cluster
     */
    fun handleBackspace(ic: InputConnection) {
        ic.beginBatchEdit()
        try {
            controller.currentTransactionId++
            // Priority 1: Selection deletion
            val hasSelection = (controller.cachedSelStart != controller.cachedSelEnd) || controller.isSelecting
            if (hasSelection) {
                deleteSelection(ic)
                controller.clearState()
                controller.isSelecting = false
                controller.service.evaluateAutoShift()
                return
            }

            // Priority 2: Active composing session undo
            if (controller.composingRaw.isNotEmpty()) {
                if (controller.isComposingStateDesynced(ic)) {
                    ic.finishComposingText()
                    controller.clearState()
                    deleteLastGraphemeOrChar(ic)
                } else {
                    performComposingBackspace(ic)
                }
                controller.service.evaluateAutoShift()
                return
            }

            // Priority 3: Macro expansion rollback
            val macro = controller.lastExpandedMacro
            if (macro != null && (System.currentTimeMillis() - macro.timestamp < 3000)) {
                val beforeText = ic.getTextBeforeCursor(macro.expandedText.length + 10, 0)?.toString() ?: ""
                if (beforeText.endsWith(macro.expandedText)) {
                    controller.lastExpandedMacro = null
                    val len = macro.expandedText.length
                    deleteBefore(ic, len)
                    controller.composingRaw.clear()
                    controller.composingRaw.append(macro.trigger)
                    controller.composingCursorIndex = macro.trigger.length
                    val (compiled, snap) = controller.inputEngine.syncWithRaw(macro.trigger, controller.compositionOwnership)
                    val cased = VietnameseCharUtils.applyCasingFromRaw(compiled, macro.trigger)
                    snap.displayText = cased
                    controller.pushImeSnapshot(
                        raw = macro.trigger,
                        cursor = macro.trigger.length,
                        shift = controller.activeComposingShiftState,
                        snap = snap
                    )
                    controller.updateComposingUI(ic, explicitCompiled = cased)
                    controller.service.evaluateAutoShift()
                    return
                }
            }
            controller.lastExpandedMacro = null

            // Priority 4: Raw Unicode Grapheme Cluster deletion (formed text deletion)
            deleteLastGraphemeOrChar(ic)
            controller.service.evaluateAutoShift()
        } finally {
            ic.endBatchEdit()
        }
    }

    /**
     * Executes word-level deletion backward (e.g. for long-press backspace or swipe delete).
     */
    fun handleDeleteWord(ic: InputConnection) {
        ic.beginBatchEdit()
        try {
            controller.currentTransactionId++
            controller.lastExpandedMacro = null
            if (controller.composingRaw.isNotEmpty()) {
                val lastLen = controller.lastSetComposingText?.length ?: 0
                controller.resetComposingUI(ic, lastLen)
                controller.service.evaluateAutoShift()
                return
            }

            val beforeText = ic.getTextBeforeCursor(100, 0) ?: ""
            if (beforeText.isNotEmpty()) {
                val trimmed = beforeText.toString().trimEnd()
                val lastSpaceIndex = trimmed.lastIndexOf(' ')
                val charsToDelete = beforeText.length - (if (lastSpaceIndex == -1) 0 else lastSpaceIndex + 1).coerceAtLeast(0)
                deleteBefore(ic, charsToDelete)
            } else {
                deleteLastGraphemeOrChar(ic)
            }
            controller.service.evaluateAutoShift()
        } finally {
            ic.endBatchEdit()
        }
    }

    /**
     * Performs backspace during an active composing session.
     * Prioritizes Snapshot-first exact state restoration, falling back to VietnameseEditReducer.
     */
    fun performComposingBackspace(ic: InputConnection) {
        // 1. Snapshot-first undo during active typing sessions
        if (controller.imeUndoCount > 1) {
            controller.popImeSnapshot()
            val prevSnap = controller.getTopImeSnapshot()
            if (prevSnap != null) {
                controller.composingRaw.clear()
                controller.composingRaw.append(prevSnap.composingRaw)
                controller.composingCursorIndex = prevSnap.composingCursorIndex
                controller.activeComposingShiftState = prevSnap.shiftState
                controller.compositionOwnership = prevSnap.ownership
                val restoredDisplay = prevSnap.displayText

                controller.inputEngine.restoreSnapshot(prevSnap.composerSnapshot)

                replaceComposingText(ic, restoredDisplay)

                if (controller.composingStartInEditor >= 0) {
                    val cursorInDisp = prevSnap.displayCursorIndex.coerceIn(0, restoredDisplay.length)
                    val newCursor = controller.composingStartInEditor + cursorInDisp
                    if (newCursor >= 0) {
                        ic.setSelection(newCursor, newCursor)
                        controller.expectedCursorStart = newCursor
                        controller.expectedCursorEnd = newCursor
                    }
                }
                return
            }
        }

        // 2. Non-snapshot / Adopted / Boundary backspace via unified VietnameseEditReducer
        val currentDisplay = controller.lastSetComposingText ?: controller.compileComposingText()
        if (currentDisplay.isEmpty()) {
            val lastLen = controller.lastSetComposingText?.length ?: 0
            controller.resetComposingUI(ic, lastLen)
            controller.clearState()
            return
        }

        // Determine exact cursor position in display text
        val cursorInDisplay = VietnameseCursorMapper.rawToDisplay(
            raw = controller.composingRaw.toString(),
            rawCursor = controller.composingCursorIndex,
            ownership = controller.compositionOwnership,
            options = controller.inputEngine.options
        )

        val result = VietnameseEditReducer.reduceBackspace(
            currentDisplay = currentDisplay,
            cursorInDisplay = cursorInDisplay,
            currentOwnership = controller.compositionOwnership,
            options = controller.inputEngine.options
        )

        applyEditResult(ic, result)
    }

    /**
     * Performs forward delete during an active composing session.
     * Uses VietnameseEditReducer.reduceDeleteForward() to mutate state.
     */
    fun performComposingDeleteForward(ic: InputConnection) {
        val currentDisplay = controller.lastSetComposingText ?: controller.compileComposingText()
        if (currentDisplay.isEmpty()) {
            val lastLen = controller.lastSetComposingText?.length ?: 0
            controller.resetComposingUI(ic, lastLen)
            controller.clearState()
            return
        }

        // Determine exact cursor position in display text
        val cursorInDisplay = VietnameseCursorMapper.rawToDisplay(
            raw = controller.composingRaw.toString(),
            rawCursor = controller.composingCursorIndex,
            ownership = controller.compositionOwnership,
            options = controller.inputEngine.options
        )

        val result = VietnameseEditReducer.reduceDeleteForward(
            currentDisplay = currentDisplay,
            cursorInDisplay = cursorInDisplay,
            currentOwnership = controller.compositionOwnership,
            options = controller.inputEngine.options
        )

        applyEditResult(ic, result)
    }

    /**
     * Executes Forward Delete (Delete key).
     */
    fun handleDeleteForward(ic: InputConnection) {
        ic.beginBatchEdit()
        try {
            controller.currentTransactionId++
            val hasSelection = (controller.cachedSelStart != controller.cachedSelEnd) || controller.isSelecting
            if (hasSelection) {
                deleteSelection(ic)
                controller.clearState()
                controller.isSelecting = false
                controller.service.evaluateAutoShift()
                return
            }

            if (controller.composingRaw.isNotEmpty()) {
                if (controller.isComposingStateDesynced(ic)) {
                    ic.finishComposingText()
                    controller.clearState()
                    deleteNextGraphemeOrChar(ic)
                } else {
                    performComposingDeleteForward(ic)
                }
                controller.service.evaluateAutoShift()
                return
            }

            deleteNextGraphemeOrChar(ic)
            controller.service.evaluateAutoShift()
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun applyEditResult(ic: InputConnection, result: VietnameseEditReducer.EditResult) {
        if (result.display.isEmpty()) {
            val lastLen = controller.lastSetComposingText?.length ?: 0
            controller.resetComposingUI(ic, lastLen)
            controller.clearState()
            return
        }

        controller.composingRaw.clear()
        controller.composingRaw.append(result.canonicalRaw)
        controller.compositionOwnership = result.ownership
        controller.composingCursorIndex = VietnameseCursorMapper.displayToRaw(
            raw = result.canonicalRaw,
            display = result.display,
            displayOffset = result.cursorInDisplay,
            ownership = result.ownership,
            options = controller.inputEngine.options
        )

        if (result.ownership == CompositionOwnership.ADOPTED_VIETNAMESE && result.parsed != null) {
            controller.inputEngine.loadAdoptedSyllable(result.syllableState, result.canonicalRaw, result.snapshots)
        } else {
            controller.inputEngine.loadLiteral(result.display)
        }

        controller.imeUndoCount = 0
        if (result.snapshots.isNotEmpty()) {
            val runningRaw = StringBuilder()
            for (snapIdx in result.snapshots.indices) {
                val snap = result.snapshots[snapIdx]
                if (snapIdx < result.canonicalRaw.length) {
                    runningRaw.append(result.canonicalRaw[snapIdx])
                }
                controller.pushImeSnapshot(
                    raw = runningRaw.toString(),
                    cursor = runningRaw.length,
                    shift = controller.activeComposingShiftState,
                    snap = snap,
                    displayCursor = snap.displayText.length
                )
            }
        } else {
            val snap = VietnameseComposer.ComposerSnapshot(
                result.syllableState,
                result.display,
                result.ownership
            )
            controller.pushImeSnapshot(
                raw = result.canonicalRaw,
                cursor = controller.composingCursorIndex,
                shift = controller.activeComposingShiftState,
                snap = snap,
                displayCursor = result.cursorInDisplay
            )
        }

        replaceComposingText(ic, result.display)

        if (controller.composingStartInEditor >= 0) {
            val newCursor = controller.composingStartInEditor + result.cursorInDisplay
            if (newCursor >= 0) {
                ic.setSelection(newCursor, newCursor)
                controller.expectedCursorStart = newCursor
                controller.expectedCursorEnd = newCursor
            }
        }
    }

    /**
     * Deletes the preceding Unicode grapheme cluster (supporting emojis, composite marks).
     */
    fun deleteLastGraphemeOrChar(ic: InputConnection) {
        val beforeText = ic.getTextBeforeCursor(20, 0)
        if (beforeText != null && beforeText.isNotEmpty()) {
            val text = beforeText.toString()
            val charsToDelete = GraphemeEditor.getBackwardGraphemeLength(text)
            if (charsToDelete > 0) {
                deleteBefore(ic, charsToDelete)
                return
            }
        }
        deleteBefore(ic, 1)
    }

    /**
     * Deletes the next Unicode grapheme cluster forward (Forward Delete).
     */
    fun deleteNextGraphemeOrChar(ic: InputConnection) {
        val afterText = ic.getTextAfterCursor(20, 0)
        if (afterText != null && afterText.isNotEmpty()) {
            val text = afterText.toString()
            val charsToDelete = GraphemeEditor.getForwardGraphemeLength(text)
            if (charsToDelete > 0) {
                ic.deleteSurroundingText(0, charsToDelete)
                return
            }
        }
        ic.deleteSurroundingText(0, 1)
    }

    /**
     * Deletes `count` characters before the cursor, using key events in immediate-commit
     * mode (so the editor treats them as real backspaces) or deleteSurroundingText otherwise.
     */
    private fun deleteBefore(ic: InputConnection, count: Int) {
        if (controller.isImmediateCommitMode()) {
            sendBackspaceEvents(ic, count)
        } else {
            ic.deleteSurroundingText(count, 0)
        }
    }

    /**
     * Deletes the active selection (or falls back to deleting a single character).
     */
    private fun deleteSelection(ic: InputConnection) {
        if (controller.isImmediateCommitMode()) {
            sendDelKey(ic)
        } else {
            ic.commitText("", 1)
        }
    }

    /**
     * Replaces the current composing text with a new display string, keeping
     * composition state consistent regardless of immediate-commit mode.
     */
    private fun replaceComposingText(ic: InputConnection, display: String) {
        if (controller.isImmediateCommitMode()) {
            val lastStr = controller.lastSetComposingText ?: ""
            sendBackspaceEvents(ic, lastStr.length)
            if (display.isNotEmpty()) {
                ic.commitText(display, 1)
            }
        } else {
            ic.setComposingText(display, 1)
        }
        controller.lastSetComposingText = display
    }

    fun sendBackspaceEvents(ic: InputConnection, count: Int) {
        for (i in 0 until count) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    fun sendDelKey(ic: InputConnection) {
        sendBackspaceEvents(ic, 1)
    }
}
