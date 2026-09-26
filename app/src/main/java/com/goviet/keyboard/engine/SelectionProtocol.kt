package com.goviet.keyboard.engine

/**
 * What a selection callback means for the preedit we currently own.
 *
 * The four verdicts are the whole vocabulary of the protocol — there is no fifth
 * "it was probably our echo, unless it took too long" case, because the callback
 * carries no timing and none is invented for it.
 */
internal enum class SelectionVerdict {
    /** The editor reported no usable position. Nothing is known, so nothing changes. */
    NO_INFO,

    /**
     * A collapsed caret inside the range we wrote. Either a delayed echo of our
     * own write or the user tapping inside their own preedit — both mean the same
     * thing, so the composition is kept and the caret adopted.
     */
    KEEP_PREEDIT,

    /** A real caret move outside the range we wrote. */
    USER_MOVE,

    /** The editor now has a selection, so the range is not ours to rewrite. */
    USER_SELECTION,
}

/**
 * Decides what [ImeInputConnectionController.onUpdateSelection] must do with a
 * selection callback. Pure: five numbers in, one verdict out, no clock, no
 * Android type, no editor call — so the rule can be read and tested on its own.
 *
 * The inputs are exactly the facts a write leaves behind: where the preedit we
 * own starts ([preeditStart], -1 when we own none) and how long it is
 * ([preeditLength]). Because every write is a single replaceText over exactly
 * that range, those two numbers are the editor's true state, which is why the
 * question "is this callback ours?" needs nothing else.
 *
 * @param newSelStart reported start, negative when the editor has no information
 * @param newSelEnd reported end, negative when unknown
 * @param composing whether the engine holds a composition
 * @param preeditStart start of the managed preedit range, negative if none
 * @param preeditLength length of the managed preedit
 */
internal fun reconcileSelection(
    newSelStart: Int,
    newSelEnd: Int,
    composing: Boolean,
    preeditStart: Int,
    preeditLength: Int
): SelectionVerdict {
    // A negative start is the editor saying "I have no selection information" —
    // Zalo reports it around focus changes — NOT a caret somewhere before the
    // start of the text. Read as a position it ended the composition and dropped
    // the preedit, so the next keystroke committed a word the user never
    // finished. Nothing is known here, so nothing is changed.
    if (newSelStart < 0) return SelectionVerdict.NO_INFO

    // An unknown end with a known start is a collapsed caret, not a selection.
    // Guessing "selection" would make the next backspace delete text the user
    // never selected.
    val end = if (newSelEnd < 0) newSelStart else newSelEnd
    if (end != newSelStart) return SelectionVerdict.USER_SELECTION

    if (composing && preeditStart >= 0) {
        val preeditEnd = preeditStart + preeditLength
        if (newSelStart >= preeditStart && newSelStart <= preeditEnd) {
            return SelectionVerdict.KEEP_PREEDIT
        }
    }

    // A caret outside the range we own (or with no preedit at all) can only be a
    // real editor-side move: the buffer cannot be written back where the editor
    // now is, so it is dropped and re-resolved from the editor's own text.
    return SelectionVerdict.USER_MOVE
}
