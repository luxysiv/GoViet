package com.goviet.keyboard.engine

/**
 * VietnameseGrammar:
 * Full Vietnamese grammar definitions: VALID_ONSETS, VALID_CODAS, VALID_RIMES.
 * Eliminates the need for dictionary lookups or hardcoded exceptions.
 */
object VietnameseGrammar {

    val VALID_ONSETS = setOf(
        "b", "c", "ch",
        "d", "đ",
        "g", "gh", "gi",
        "h",
        "k", "kh",
        "l",
        "m",
        "n", "nh", "ng", "ngh",
        "p", "ph",
        "qu",
        "r",
        "s",
        "t", "th", "tr",
        "v",
        "x"
    )

    val VALID_CODAS = listOf(
        "ng", "nh", "ch", "m", "p", "n", "t", "c"
    )

    private val CODA_PREFIXES: Set<String> = hashSetOf<String>().apply {
        for (coda in VALID_CODAS) {
            for (len in 1..coda.length) {
                add(coda.substring(0, len))
            }
        }
    }

    val VALID_RIMES: Set<String> = hashSetOf(
        // --- Single vowel / Open rimes (No coda) ---
        "a", "ă", "â", "e", "ê", "i", "o", "ô", "ơ", "u", "ư", "y", "oo",

        "ai", "ao", "au", "ay", "âu", "ây", "eo", "êu", "ia", "iu", "oi", "ôi", "ơi",
        "ua", "ui", "uy", "ưa", "ưi", "ưu", "iêu", "yêu", "ươu", "ươi",
        "oa", "oă", "oe", "oai", "oao", "oay", "oeo",
        "uâ", "uây", "uê", "uôi", "uơ", "uya", "uyu", "uyên", "uyêt",

        // --- Coda -m ---
        "am", "ăm", "âm", "em", "êm", "im", "om", "ôm", "ơm", "um", "ưm",
        "oam", "oăm", "uâm", "uôm", "iêm", "yêm", "ươm",

        // --- Coda -p ---
        "ap", "ăp", "âp", "ep", "êp", "ip", "op", "ôp", "ơp", "up", "ưp",
        "oap", "oăp", "uâp", "uôp", "iêp", "ươp",

        // --- Coda -n ---
        "an", "ăn", "ân", "en", "ên", "in", "on", "ôn", "ơn", "un", "ưn", "yn",
        "oan", "oăn", "uân", "uên", "uôn", "iên", "yên", "ươn", "uyn", "uyên",

        // --- Coda -t ---
        "at", "ăt", "ât", "et", "êt", "it", "ot", "ôt", "ơt", "ut", "ưt", "yt",
        "oat", "oăt", "uât", "uêt", "uôt", "iêt", "yêt", "ươt", "uyt", "uyêt",

        // --- Coda -ng ---
        "ang", "ăng", "âng", "eng", "oeng", "ong", "ông", "ung", "ưng",
        "oang", "oăng", "uâng", "uông", "iêng", "yêng", "ương", "oong", "uơng", "êng",

        // --- Coda -c ---
        "ac", "ăc", "âc", "ec", "oc", "ôc", "uc", "ưc",
        "oac", "oăc", "uâc", "uôc", "iêc", "ươc", "ooc", "uơc", "êc",

        // --- Coda -nh ---
        "anh", "ênh", "inh", "ynh", "oanh", "uynh", "uênh",

        // --- Coda -ch ---
        "ach", "êch", "ich", "ych", "oach", "uych", "uêch"
    )

    private val STRIPPED_VALID_RIMES: Set<String> = hashSetOf<String>().apply {
        for (rime in VALID_RIMES) {
            add(stripDiacritics(rime))
        }
    }

    private val RIME_PREFIXES: Set<String> = hashSetOf<String>().apply {
        for (rime in VALID_RIMES) {
            for (len in 1..rime.length) {
                val sub = rime.substring(0, len)
                add(sub)
                add(stripDiacritics(sub))
            }
        }
    }

    private fun stripDiacritics(str: String): String {
        val sb = StringBuilder()
        for (c in str) {
            val stripped = when (c) {
                'ă', 'â' -> 'a'
                'ê' -> 'e'
                'ô', 'ơ' -> 'o'
                'ư' -> 'u'
                'đ' -> 'd'
                else -> c
            }
            sb.append(stripped)
        }
        return sb.toString()
    }

    fun isValidOnset(onset: String): Boolean {
        if (onset.isEmpty()) return true
        return VALID_ONSETS.contains(onset.lowercase())
    }

    fun isValidCoda(coda: String): Boolean {
        if (coda.isEmpty()) return true
        return VALID_CODAS.contains(coda.lowercase())
    }

    fun isCodaPrefix(coda: String): Boolean {
        if (coda.isEmpty()) return true
        return CODA_PREFIXES.contains(coda.lowercase())
    }

    fun isValidRime(rime: String): Boolean {
        val lower = rime.lowercase()
        if (VALID_RIMES.contains(lower)) return true
        val stripped = stripDiacritics(lower)
        return STRIPPED_VALID_RIMES.contains(stripped)
    }

    fun isRimePrefix(prefix: String): Boolean {
        val lower = prefix.lowercase()
        if (RIME_PREFIXES.contains(lower)) return true
        val stripped = stripDiacritics(lower)
        return RIME_PREFIXES.contains(stripped)
    }

    fun isValidWord(word: String): Boolean {
        if (word.isEmpty()) return false
        val stripped = VietnameseUnicode.stripToneFromWord(word).lowercase()
        for (len in minOf(4, stripped.length) downTo 1) {
            val onset = stripped.substring(0, len)
            if (isValidOnset(onset)) {
                val rime = stripped.substring(len)
                if (isValidRime(rime)) {
                    return true
                }
            }
        }
        return isValidRime(stripped)
    }
}
