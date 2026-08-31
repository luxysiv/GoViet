package com.goviet.keyboard.engine

/**
 * Classification of input boundaries for consistent word breaks, commit triggers,
 * sentence endings, and auto-capitalization across the engine and UI.
 */
enum class InputBoundary {
    NONE,
    WHITESPACE,
    WORD_SEPARATOR,
    SENTENCE_TERMINATOR,
    HARD_BREAK
}

object BoundaryClassifier {

    fun classify(c: Char, isTelexMode: Boolean = false): InputBoundary {
        if (isTelexMode && (c == '[' || c == ']' || c == '{' || c == '}')) {
            return InputBoundary.NONE
        }
        return when {
            c == '\n' || c == '\r' -> InputBoundary.HARD_BREAK
            c.isWhitespace() -> InputBoundary.WHITESPACE
            c == '.' || c == '?' || c == '!' -> InputBoundary.SENTENCE_TERMINATOR
            c == ',' || c == ';' || c == ':' || c == '-' || c == '/' || c == '(' || c == ')' ||
            c == '[' || c == ']' || c == '{' || c == '}' || c == '\"' || c == '\'' || c == '«' ||
            c == '»' || c == '`' || c == '~' || c == '@' || c == '#' || c == '$' || c == '%' ||
            c == '^' || c == '&' || c == '*' || c == '_' || c == '=' || c == '+' || c == '|' ||
            c == '\\' || c == '<' || c == '>' -> InputBoundary.WORD_SEPARATOR
            else -> InputBoundary.NONE
        }
    }

    fun isBoundaryChar(c: Char, isTelexMode: Boolean = false): Boolean {
        return classify(c, isTelexMode) != InputBoundary.NONE
    }

    fun isWhitespace(c: Char): Boolean = classify(c) == InputBoundary.WHITESPACE

    fun isHardBreak(c: Char): Boolean = classify(c) == InputBoundary.HARD_BREAK

    fun isSentenceTerminator(c: Char): Boolean = classify(c) == InputBoundary.SENTENCE_TERMINATOR

    fun isSentenceTerminator(key: String): Boolean {
        if (key == "ENTER" || key == "\n" || key == "\r") return true
        if (key.length == 1) return isSentenceTerminator(key[0])
        return false
    }

    fun isWordSeparator(c: Char, isTelexMode: Boolean = false): Boolean =
        classify(c, isTelexMode) == InputBoundary.WORD_SEPARATOR

    fun isBoundary(key: String, isTelexMode: Boolean = false): Boolean {
        if (key.isEmpty()) return false
        if (key == "SPACE" || key == "ENTER") return true
        if (key.length == 1) {
            return isBoundaryChar(key[0], isTelexMode)
        }
        return false
    }
}
