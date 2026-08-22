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
 * Telex transformation operations.
 */
enum class TransformOperation {
    A_CIRCUMFLEX,  // aa -> â
    E_CIRCUMFLEX,  // ee -> ê
    O_CIRCUMFLEX,  // oo -> ô
    A_BREVE,       // aw -> ă
    O_HORN,        // ow -> ơ
    U_HORN,        // uw -> ư
    D_BAR,         // dd -> đ

    ACUTE,         // s
    GRAVE,         // f
    HOOK,          // r
    TILDE,         // x
    DOT,           // j
    REMOVE_TONE    // z
}

/**
 * Vietnamese syllable 4-component phonological structure:
 * Syllable = <Onset, Nucleus, Coda, Tone>
 */
data class SyllableStruct(
    val onset: String = "",
    val nucleus: String = "",
    val coda: String = "",
    val tone: Tone = Tone.NONE,
    val isDBar: Boolean = false,
    val isNonVietnamese: Boolean = false
) {
    val rime: String get() = when {
        onset.lowercase() == "gi" && nucleus.isEmpty() -> "i" + coda
        onset.lowercase() == "qu" -> "u" + nucleus + coda
        else -> nucleus + coda
    }
}

/**
 * State of the incremental composer.
 */
data class ComposerState(
    val committedText: String = "",
    val displayText: String = "",
    val activeRaw: String = "",
    val operation: TransformOperation? = null,
    val operationKey: Char? = null,
    val isCommitted: Boolean = false,
    val isVietnameseTransforming: Boolean = false,
    val isNonVietnameseWord: Boolean = false,
    val cancelledModifiers: Set<Char> = emptySet()
)

/**
 * Result returned after processing a key event.
 */
data class EngineResult(
    val text: String,
    val consumed: Boolean = true,
    val composing: Boolean = true
)

/**
 * Engine configuration options.
 */
data class EngineOptions(
    var macroEnabled: Boolean = false,
    var alwaysMacro: Boolean = false,
    var directW: Boolean = false,
    var oldTonePlacement: Boolean = false
)

// Alias for backward compatibility if needed
typealias GoVietOptions = EngineOptions
