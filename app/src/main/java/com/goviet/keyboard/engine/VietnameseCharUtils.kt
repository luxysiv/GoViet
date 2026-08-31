package com.goviet.keyboard.engine

/**
 * VietnameseCharUtils:
 * Casing utilities, character mapping, and Unicode NFC normalization.
 */
object VietnameseCharUtils {

    fun normalizeNfc(text: String): String {
        if (text.isEmpty()) return text
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
    }

    fun applyCasingFromRaw(compiled: String, raw: String): String {
        if (compiled.isEmpty() || raw.isEmpty()) return normalizeNfc(compiled)

        var hasUpperInRaw = false
        var isAllUpper = true
        val rawLen = raw.length
        for (i in 0 until rawLen) {
            val c = raw[i]
            val isLtr = c in 'a'..'z' || c in 'A'..'Z' || c.lowercaseChar() != c.uppercaseChar()
            if (isLtr) {
                if (c.isUpperCase()) {
                    hasUpperInRaw = true
                } else {
                    isAllUpper = false
                }
            }
        }

        if (!hasUpperInRaw) {
            val builder = StringBuilder(compiled.length)
            for (i in 0 until compiled.length) {
                builder.append(compiled[i].lowercaseChar())
            }
            return normalizeNfc(builder.toString())
        }

        if (isAllUpper) {
            val builder = StringBuilder(compiled.length)
            for (i in 0 until compiled.length) {
                builder.append(compiled[i].uppercaseChar())
            }
            return normalizeNfc(builder.toString())
        }

        val isFirstUpper = raw[0].isUpperCase()
        val builder = StringBuilder(compiled.length)
        var rawIdx = 0

        val compiledLen = compiled.length
        for (i in 0 until compiledLen) {
            val char = compiled[i]
            val baseCompiled = getBaseChar(char)

            var matchedChar: Char? = null
            var tempIdx = rawIdx
            while (tempIdx < rawLen) {
                val rawChar = raw[tempIdx]
                if (getBaseChar(rawChar) == baseCompiled) {
                    matchedChar = rawChar
                    rawIdx = tempIdx + 1
                    break
                }
                tempIdx++
            }

            if (matchedChar != null) {
                if (matchedChar.isUpperCase()) {
                    builder.append(char.uppercaseChar())
                } else if (char.isUpperCase()) {
                    builder.append(char)
                } else {
                    builder.append(char.lowercaseChar())
                }
            } else {
                if (char.isUpperCase()) {
                    builder.append(char)
                } else if (isFirstUpper && i == 0) {
                    builder.append(char.uppercaseChar())
                } else {
                    builder.append(char.lowercaseChar())
                }
            }
        }
        return normalizeNfc(builder.toString())
    }

    fun getBaseChar(c: Char): Char {
        return VietnameseUnicode.stripDiacritics(VietnameseUnicode.stripTone(c)).lowercaseChar()
    }
}
