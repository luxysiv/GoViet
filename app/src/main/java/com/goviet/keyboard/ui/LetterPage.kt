package com.goviet.keyboard.ui

/**
 * The pages of the letter grid.
 *
 * This was a `String` that changed meaning twice over: the root view wrote
 * "QWERTY"/"SYMBOLS" into it, and the view translated that into a second
 * string, "ABC"/"SYM1"/"SYM2", for [KeyboardLayout]. Two string state machines
 * describing one page, and the translation step is where the symbol picker bug
 * came from. The view now exposes [StandardLetterGridView.page] directly, and
 * this enum is what both sides speak.
 */
enum class LetterPage {
    /** Letters and the 123/abc/emoji row. */
    LETTERS,

    /** First symbol page: digits and the common punctuation. */
    SYMBOLS_1,

    /** Second symbol page: currency, maths, arrows, and the rest. */
    SYMBOLS_2;

    /** The other symbol page, for the key that walks between the two. */
    fun other(): LetterPage = if (this == SYMBOLS_1) SYMBOLS_2 else SYMBOLS_1
}
