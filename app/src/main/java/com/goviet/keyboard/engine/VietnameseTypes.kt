package com.goviet.keyboard.engine

/**
 * Vietnamese tones.
 */
enum class Tone(val index: Int) {
    NONE(0),
    ACUTE(1),  // acute ('s')
    GRAVE(2),  // grave ('f')
    HOOK(3),   // hook above ('r')
    TILDE(4),  // tilde ('x')
    DOT(5);    // dot below ('j')

    companion object {
        fun fromKey(c: Char): Tone? = when (c.lowercaseChar()) {
            's' -> ACUTE
            'f' -> GRAVE
            'r' -> HOOK
            'x' -> TILDE
            'j' -> DOT
            'z' -> NONE
            else -> null
        }
    }
}

/**
 * Vietnamese composition ownership & editing modes.
 */
enum class CompositionOwnership {
    /**
     * String created directly by FSM from active keystrokes.
     * Allowed full Telex transformations.
     */
    LIVE_VIETNAMESE,

    /**
     * String adopted/re-parsed from editor and verified as a valid Vietnamese syllable.
     */
    ADOPTED_VIETNAMESE,

    /**
     * Literal string (e.g. completed literal words, codes, symbols).
     * Protected from unintended Telex re-interpretation.
     */
    EDITED_LITERAL
}

/**
 * Modern Zero-Allocation Event Flow modeled via Sealed Interface.
 */
sealed interface CompositionResult {
    /**
     * Active composing buffer updated with new display text and active span range.
     */
    data class Update(
        val text: CharSequence,
        val composingRange: IntRange = 0 until text.length
    ) : CompositionResult

    /**
     * Syllable is committed and a new character/word is started immediately.
     */
    data class CommitAndStartNew(
        val commitText: String,
        val newChar: Char
    ) : CompositionResult

    /**
     * Key is not consumed by Vietnamese composer and should be passed directly to editor.
     */
    data object PassThrough : CompositionResult
}

val CompositionResult.text: String
    get() = when (this) {
        is CompositionResult.Update -> text.toString()
        is CompositionResult.CommitAndStartNew -> commitText + newChar
        is CompositionResult.PassThrough -> ""
    }

/**
 * Engine configuration options.
 */
data class EngineOptions(
    var macroEnabled: Boolean = false,
    var alwaysMacro: Boolean = false,
    var directW: Boolean = false,
    var oldTonePlacement: Boolean = false
)
