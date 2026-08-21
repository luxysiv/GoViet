package com.goviet.keyboard.engine

/**
 * VietnameseUnicode:
 * Unicode tables and Vietnamese diacritics / tone mappings.
 */
object VietnameseUnicode {

    fun applyTone(char: Char, tone: Tone): Char {
        return TonePositionMap.applyToneToChar(char, tone)
    }

    fun stripTone(char: Char): Char {
        return when (char) {
            'á', 'à', 'ả', 'ã', 'ạ' -> 'a'
            'ắ', 'ằ', 'ẳ', 'ẵ', 'ặ' -> 'ă'
            'ấ', 'ầ', 'ẩ', 'ẫ', 'ậ' -> 'â'
            'é', 'è', 'ẻ', 'ẽ', 'ẹ' -> 'e'
            'ế', 'ề', 'ể', 'ễ', 'ệ' -> 'ê'
            'í', 'ì', 'ỉ', 'ĩ', 'ị' -> 'i'
            'ó', 'ò', 'ỏ', 'õ', 'ọ' -> 'o'
            'ố', 'ồ', 'ổ', 'ỗ', 'ộ' -> 'ô'
            'ớ', 'ờ', 'ở', 'ỡ', 'ợ' -> 'ơ'
            'ú', 'ù', 'ủ', 'ũ', 'ụ' -> 'u'
            'ứ', 'ừ', 'ử', 'ữ', 'ự' -> 'ư'
            'ý', 'ỳ', 'ỷ', 'ỹ', 'ỵ' -> 'y'

            'Á', 'À', 'Ả', 'Ã', 'Ạ' -> 'A'
            'Ắ', 'Ằ', 'Ẳ', 'Ẵ', 'Ặ' -> 'Ă'
            'Ấ', 'Ầ', 'Ẩ', 'Ẫ', 'Ậ' -> 'Â'
            'É', 'È', 'Ẻ', 'Ẽ', 'Ẹ' -> 'E'
            'Ế', 'Ề', 'Ể', 'Ễ', 'Ệ' -> 'Ê'
            'Í', 'Ì', 'Ỉ', 'Ĩ', 'Ị' -> 'I'
            'Ó', 'Ò', 'Ỏ', 'Õ', 'Ọ' -> 'O'
            'Ố', 'Ồ', 'Ổ', 'Ỗ', 'Ộ' -> 'Ô'
            'Ớ', 'Ờ', 'Ở', 'Ỡ', 'Ợ' -> 'Ơ'
            'Ú', 'Ù', 'Ủ', 'Ũ', 'Ụ' -> 'U'
            'Ứ', 'Ừ', 'Ử', 'Ữ', 'Ự' -> 'Ư'
            'Ý', 'Ỳ', 'Ỷ', 'Ỹ', 'Ỵ' -> 'Y'
            else -> char
        }
    }

    fun stripDiacritics(char: Char): Char {
        return when (stripTone(char)) {
            'ă', 'â' -> 'a'
            'Ă', 'Â' -> 'A'
            'ê' -> 'e'
            'Ê' -> 'E'
            'ô', 'ơ' -> 'o'
            'Ô', 'Ơ' -> 'O'
            'ư' -> 'u'
            'Ư' -> 'U'
            'đ' -> 'd'
            'Đ' -> 'D'
            else -> stripTone(char)
        }
    }

    fun stripToneFromWord(word: String): String {
        val sb = StringBuilder()
        for (c in word) {
            sb.append(stripTone(c))
        }
        return sb.toString()
    }

    fun findToneTargetIndex(
        vowels: List<Char>,
        coda: String,
        onset: String,
        modernStyle: Boolean = true
    ): Int {
        if (vowels.isEmpty()) return -1
        if (vowels.size == 1) return 0

        val vStrLower = vowels.map { stripTone(it).lowercaseChar() }.joinToString("")
        val onsetLower = onset.lowercase()
        val hasCoda = coda.isNotEmpty()

        // 1. Triple vowel clusters: iêu, yêu, ươu, oai, oao, oay, oeo, uây, uôi, uya, uyu, uyê
        if (vowels.size == 3) {
            if (vStrLower == "uyê" || vStrLower == "uye") {
                return 2
            }
            return 1
        }

        // 2. Double vowel clusters:
        if (vowels.size == 2) {
            val v0 = vStrLower[0]
            val v1 = vStrLower[1]

            // "oo" rime (oó, oọ, coó, coọ, boóng, coóng, soóc...)
            if (vStrLower == "oo") {
                return 1
            }

            // qu -> 'u' is glide, tone marks on following vowel
            if (onsetLower == "qu" || onsetLower.endsWith("qu")) {
                return 1
            }

            // gi -> if followed by a vowel, tone marks on the following vowel
            if (onsetLower == "gi" || onsetLower == "g") {
                if (v0 == 'i') return 1
            }

            // ươ, ưa, iê, ia, uô, ua
            if (vStrLower == "ươ" || vStrLower == "uo") {
                return if (hasCoda) 1 else 0
            }
            if (vStrLower == "ưa") {
                return 0
            }
            if (vStrLower == "iê") {
                return 1
            }
            if (vStrLower == "ia") {
                return 0
            }
            if (vStrLower == "uô") {
                return 1
            }
            if (vStrLower == "ua") {
                return 0
            }

            // With coda: tone marks on the second vowel (hoàn, toán, luật...)
            if (hasCoda) {
                return 1
            }

            // Open rimes without coda (oa, oe, uy, uơ)
            if (vStrLower == "oa" || vStrLower == "oe" || vStrLower == "uy" || vStrLower == "uơ") {
                return if (modernStyle) 1 else 0
            }

            // Other open rimes: ai, ao, au, ay, âu, ây, eo, êu, oi, ôi, ơi, ui, ưi, ưu
            return 0
        }

        return vowels.size - 1
    }
}

/**
 * Decompose Vietnamese diacritic characters into raw Telex keystroke sequence.
 * Used when adopting word at cursor or processing backspace.
 */
object VietnameseCharDecomposer {
    fun decomposeChar(c: Char): String {
        return when (c) {
            'á' -> "as"; 'à' -> "af"; 'ả' -> "ar"; 'ã' -> "ax"; 'ạ' -> "aj"
            'â' -> "aa"; 'ấ' -> "aas"; 'ầ' -> "aaf"; 'ẩ' -> "aar"; 'ẫ' -> "aax"; 'ậ' -> "aaj"
            'ă' -> "aw"; 'ắ' -> "aws"; 'ằ' -> "awf"; 'ẳ' -> "awr"; 'ẵ' -> "awx"; 'ặ' -> "awj"
            'é' -> "es"; 'è' -> "ef"; 'ẻ' -> "er"; 'ẽ' -> "ex"; 'ẹ' -> "ej"
            'ê' -> "ee"; 'ế' -> "ees"; 'ề' -> "eef"; 'ể' -> "eer"; 'ễ' -> "eex"; 'ệ' -> "eej"
            'í' -> "is"; 'ì' -> "if"; 'ỉ' -> "ir"; 'ĩ' -> "ix"; 'ị' -> "ij"
            'ó' -> "os"; 'ò' -> "of"; 'ỏ' -> "or"; 'õ' -> "ox"; 'ọ' -> "oj"
            'ô' -> "oo"; 'ố' -> "oos"; 'ồ' -> "oof"; 'ổ' -> "oor"; 'ỗ' -> "oox"; 'ộ' -> "ooj"
            'ơ' -> "ow"; 'ớ' -> "ows"; 'ờ' -> "owf"; 'ở' -> "owr"; 'ỡ' -> "owx"; 'ợ' -> "owj"
            'ú' -> "us"; 'ù' -> "uf"; 'ủ' -> "ur"; 'ũ' -> "ux"; 'ụ' -> "uj"
            'ư' -> "uw"; 'ứ' -> "uws"; 'ừ' -> "uwf"; 'ử' -> "uwr"; 'ữ' -> "uwx"; 'ự' -> "uwj"
            'ý' -> "ys"; 'ỳ' -> "yf"; 'ỷ' -> "yr"; 'ỹ' -> "yx"; 'ỵ' -> "yj"
            'đ' -> "dd"
            'Á' -> "As"; 'À' -> "Af"; 'Ả' -> "Ar"; 'Ã' -> "Ax"; 'Ạ' -> "Aj"
            'Â' -> "Aa"; 'Ấ' -> "Aas"; 'Ầ' -> "Aaf"; 'Ẩ' -> "Aar"; 'Ẫ' -> "Aax"; 'Ậ' -> "Aaj"
            'Ă' -> "Aw"; 'Ắ' -> "Aws"; 'Ằ' -> "Awf"; 'Ẳ' -> "Awr"; 'Ẵ' -> "Awx"; 'Ặ' -> "Awj"
            'É' -> "Es"; 'È' -> "Ef"; 'Ẻ' -> "Er"; 'Ẽ' -> "Ex"; 'Ẹ' -> "Ej"
            'Ê' -> "Ee"; 'Ế' -> "Ees"; 'Ề' -> "Eef"; 'Ể' -> "Eer"; 'Ễ' -> "Eex"; 'Ệ' -> "Eej"
            'Í' -> "Is"; 'Ì' -> "If"; 'Ỉ' -> "Ir"; 'Ĩ' -> "Ix"; 'Ị' -> "Ij"
            'Ó' -> "Os"; 'Ò' -> "Of"; 'Ỏ' -> "Or"; 'Õ' -> "Ox"; 'Ọ' -> "Oj"
            'Ô' -> "Oo"; 'Ố' -> "Oos"; 'Ồ' -> "Oof"; 'Ổ' -> "Oor"; 'Ỗ' -> "Oox"; 'Ộ' -> "Ooj"
            'Ơ' -> "Ow"; 'Ớ' -> "Ows"; 'Ờ' -> "Owf"; 'Ở' -> "Owr"; 'Ỡ' -> "Owx"; 'Ợ' -> "Owj"
            'Ú' -> "Us"; 'Ù' -> "Uf"; 'Ủ' -> "Ur"; 'Ũ' -> "Ux"; 'Ụ' -> "Uj"
            'Ư' -> "Uw"; 'Ứ' -> "Uws"; 'Ừ' -> "Uwf"; 'Ử' -> "Uwr"; 'Ữ' -> "Uwx"; 'Ự' -> "Uwj"
            'Ý' -> "Ys"; 'Ỳ' -> "Yf"; 'Ỷ' -> "Yr"; 'Ỹ' -> "Yx"; 'Ỵ' -> "Yj"
            'Đ' -> "Dd"
            else -> c.toString()
        }
    }
}

/**
 * Casing utilities and Unicode NFC normalization.
 */
object GoVietCharUtils {
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
        return when (c) {
            'a', 'A', 'à', 'À', 'á', 'Á', 'ả', 'Ả', 'ã', 'Ã', 'ạ', 'Ạ',
            'ă', 'Ă', 'ằ', 'Ằ', 'ắ', 'Ắ', 'ẳ', 'Ẳ', 'ẵ', 'Ẵ', 'ặ', 'Ặ',
            'â', 'Â', 'ầ', 'Ầ', 'ấ', 'Ấ', 'ẩ', 'Ẩ', 'ẫ', 'Ẫ', 'ậ', 'Ậ' -> 'a'
            'e', 'E', 'è', 'È', 'é', 'É', 'ẻ', 'Ẻ', 'ẽ', 'Ẽ', 'ẹ', 'Ẹ',
            'ê', 'Ê', 'ề', 'Ề', 'ế', 'Ế', 'ể', 'Ể', 'ễ', 'Ễ', 'ệ', 'Ệ' -> 'e'
            'i', 'I', 'ì', 'Ì', 'í', 'Í', 'ỉ', 'Ỉ', 'ĩ', 'Ĩ', 'ị', 'Ị' -> 'i'
            'o', 'O', 'ò', 'Ò', 'ó', 'Ó', 'ỏ', 'Ỏ', 'õ', 'Õ', 'ọ', 'Ọ',
            'ô', 'Ô', 'ồ', 'Ồ', 'ố', 'Ố', 'ổ', 'Ổ', 'ỗ', 'Ỗ', 'ộ', 'Ộ',
            'ơ', 'Ơ', 'ờ', 'Ờ', 'ớ', 'Ớ', 'ở', 'Ở', 'ỡ', 'Ỡ', 'ợ', 'Ợ' -> 'o'
            'u', 'U', 'ù', 'Ù', 'ú', 'Ú', 'ủ', 'Ủ', 'ũ', 'Ũ', 'ụ', 'Ụ',
            'ư', 'Ư', 'ừ', 'Ừ', 'ứ', 'Ứ', 'ử', 'Ử', 'ữ', 'Ữ', 'ự', 'Ự' -> 'u'
            'y', 'Y', 'ỳ', 'Ỳ', 'ý', 'Ý', 'ỷ', 'Ỷ', 'ỹ', 'Ỹ', 'ỵ', 'Ỵ' -> 'y'
            'd', 'D', 'đ', 'Đ' -> 'd'
            else -> c.lowercaseChar()
        }
    }
}
