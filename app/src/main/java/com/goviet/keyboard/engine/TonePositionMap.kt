package com.goviet.keyboard.engine

/**
 * TonePositionMap:
 * Lookup table for tone mark placement (211 rimes including standard and RAW representations)
 * and tone combination table TONE_COMBINE_MAP (12 vowels x 5 tones).
 * Central source of truth for tone mark placement and nucleus prefix validation.
 */
object TonePositionMap {

    /** Valid initial consonant clusters in Vietnamese — used to detect invalid consonant clusters */
    val VALID_CONSONANT_CLUSTERS = setOf(
        "ch", "gh", "gi", "kh", "ng", "ngh", "nh", "ph", "qu", "th", "tr"
    )

    /**
     * Standard Unicode NFC tone combination table.
     * 12 vowels with type-A transforms x 5 tones (Tone enum).
     */
    val TONE_COMBINE_MAP: Map<Char, Map<Tone, Char>> = mapOf(
        'a' to mapOf(Tone.ACUTE to 'á', Tone.GRAVE to 'à', Tone.HOOK to 'ả', Tone.TILDE to 'ã', Tone.DOT to 'ạ'),
        'ă' to mapOf(Tone.ACUTE to 'ắ', Tone.GRAVE to 'ằ', Tone.HOOK to 'ẳ', Tone.TILDE to 'ẵ', Tone.DOT to 'ặ'),
        'â' to mapOf(Tone.ACUTE to 'ấ', Tone.GRAVE to 'ầ', Tone.HOOK to 'ẩ', Tone.TILDE to 'ẫ', Tone.DOT to 'ậ'),
        'e' to mapOf(Tone.ACUTE to 'é', Tone.GRAVE to 'è', Tone.HOOK to 'ẻ', Tone.TILDE to 'ẽ', Tone.DOT to 'ẹ'),
        'ê' to mapOf(Tone.ACUTE to 'ế', Tone.GRAVE to 'ề', Tone.HOOK to 'ể', Tone.TILDE to 'ễ', Tone.DOT to 'ệ'),
        'i' to mapOf(Tone.ACUTE to 'í', Tone.GRAVE to 'ì', Tone.HOOK to 'ỉ', Tone.TILDE to 'ĩ', Tone.DOT to 'ị'),
        'o' to mapOf(Tone.ACUTE to 'ó', Tone.GRAVE to 'ò', Tone.HOOK to 'ỏ', Tone.TILDE to 'õ', Tone.DOT to 'ọ'),
        'ô' to mapOf(Tone.ACUTE to 'ố', Tone.GRAVE to 'ồ', Tone.HOOK to 'ổ', Tone.TILDE to 'ỗ', Tone.DOT to 'ộ'),
        'ơ' to mapOf(Tone.ACUTE to 'ớ', Tone.GRAVE to 'ờ', Tone.HOOK to 'ở', Tone.TILDE to 'ỡ', Tone.DOT to 'ợ'),
        'u' to mapOf(Tone.ACUTE to 'ú', Tone.GRAVE to 'ù', Tone.HOOK to 'ủ', Tone.TILDE to 'ũ', Tone.DOT to 'ụ'),
        'ư' to mapOf(Tone.ACUTE to 'ứ', Tone.GRAVE to 'ừ', Tone.HOOK to 'ử', Tone.TILDE to 'ữ', Tone.DOT to 'ự'),
        'y' to mapOf(Tone.ACUTE to 'ý', Tone.GRAVE to 'ỳ', Tone.HOOK to 'ỷ', Tone.TILDE to 'ỹ', Tone.DOT to 'ỵ')
    )

    /**
     * Lookup table of 211 rimes (including standard and RAW representations).
     */
    val TONE_POSITION_MAP: Map<String, Int> = mapOf(
        "a" to 0,
        "e" to 0,
        "i" to 0,
        "o" to 0,
        "u" to 0,
        "y" to 0,
        "ê" to 0,
        "ô" to 0,
        "ơ" to 0,
        "ư" to 0,
        "ac" to 0,
        "ai" to 0,
        "am" to 0,
        "an" to 0,
        "ao" to 0,
        "ap" to 0,
        "at" to 0,
        "au" to 0,
        "ay" to 0,
        "ec" to 0,
        "em" to 0,
        "en" to 0,
        "eo" to 0,
        "ep" to 0,
        "et" to 0,
        "eu" to 0,
        "ia" to 0,
        "ie" to 1,
        "ieu" to 1,
        "im" to 0,
        "in" to 0,
        "ip" to 0,
        "it" to 0,
        "iu" to 0,
        "iê" to 1,
        "iêu" to 1,
        "oa" to 1,
        "oă" to 1,
        "oc" to 0,
        "oe" to 1,
        "oec" to 1,
        "oen" to 1,
        "oep" to 1,
        "oet" to 1,
        "oi" to 0,
        "om" to 0,
        "on" to 0,
        "oo" to 1,
        "op" to 0,
        "ot" to 0,
        "ua" to 0,
        "uâ" to 1,
        "uc" to 0,
        "ue" to 1,
        "ui" to 0,
        "um" to 0,
        "un" to 0,
        "uo" to 1,
        "up" to 0,
        "ut" to 0,
        "uu" to 0,
        "uy" to 1,
        "uê" to 1,
        "uô" to 1,
        "uơ" to 1,
        "ye" to 1,
        "yeu" to 1,
        "yn" to 0,
        "yt" to 0,
        "yê" to 1,
        "yêu" to 1,
        "âc" to 0,
        "âm" to 0,
        "ân" to 0,
        "âp" to 0,
        "ât" to 0,
        "âu" to 0,
        "ây" to 0,
        "êm" to 0,
        "ên" to 0,
        "êp" to 0,
        "êt" to 0,
        "êu" to 0,
        "ôc" to 0,
        "ôi" to 0,
        "ôm" to 0,
        "ôn" to 0,
        "ôp" to 0,
        "ôt" to 0,
        "ăc" to 0,
        "ăm" to 0,
        "ăn" to 0,
        "ăp" to 0,
        "ăt" to 0,
        "ơi" to 0,
        "ơm" to 0,
        "ơn" to 0,
        "ơp" to 0,
        "ơt" to 0,
        "ưa" to 0,
        "ưc" to 0,
        "ưi" to 0,
        "ưm" to 0,
        "ưn" to 0,
        "ưp" to 0,
        "ưt" to 0,
        "ưu" to 0,
        "ươ" to 1,
        "ach" to 0,
        "ang" to 0,
        "anh" to 0,
        "ech" to 0,
        "eng" to 0,
        "enh" to 0,
        "ich" to 0,
        "iec" to 1,
        "iem" to 1,
        "ien" to 1,
        "iep" to 1,
        "iet" to 1,
        "inh" to 0,
        "iêc" to 1,
        "iêm" to 1,
        "iên" to 1,
        "iêp" to 1,
        "iêt" to 1,
        "oac" to 1,
        "oai" to 1,
        "oam" to 1,
        "oan" to 1,
        "oao" to 1,
        "oap" to 1,
        "oat" to 1,
        "oay" to 1,
        "oeo" to 1,
        "ong" to 0,
        "ooc" to 1,
        "oăc" to 1,
        "oăm" to 1,
        "oăn" to 1,
        "oăp" to 1,
        "oăt" to 1,
        "uac" to 1,
        "uam" to 1,
        "uan" to 1,
        "uap" to 1,
        "uat" to 1,
        "uau" to 1,
        "uay" to 1,
        "uen" to 1,
        "uet" to 1,
        "ueu" to 1,
        "ung" to 0,
        "uoc" to 1,
        "uoi" to 1,
        "uom" to 1,
        "uon" to 1,
        "uop" to 1,
        "uot" to 1,
        "uou" to 1,
        "uya" to 1,
        "uyn" to 1,
        "uyp" to 1,
        "uyt" to 1,
        "uyu" to 1,
        "uyê" to 1,
        "uâc" to 1,
        "uâm" to 1,
        "uân" to 1,
        "uâp" to 1,
        "uât" to 1,
        "uâu" to 1,
        "uây" to 1,
        "uên" to 1,
        "uêt" to 1,
        "uêu" to 1,
        "uôc" to 1,
        "uôi" to 1,
        "uôm" to 1,
        "uôn" to 1,
        "uôp" to 1,
        "uôt" to 1,
        "uơi" to 1,
        "ych" to 0,
        "yem" to 1,
        "yen" to 1,
        "yet" to 1,
        "ynh" to 0,
        "yêm" to 1,
        "yên" to 1,
        "yêt" to 1,
        "âng" to 0,
        "êch" to 0,
        "êng" to 0,
        "ênh" to 0,
        "ông" to 0,
        "ăng" to 0,
        "ưng" to 0,
        "ươc" to 1,
        "ươi" to 1,
        "ươm" to 1,
        "ươn" to 1,
        "ươp" to 1,
        "ươt" to 1,
        "ươu" to 1,
        "ieng" to 1,
        "iêng" to 1,
        "oach" to 1,
        "oang" to 1,
        "oanh" to 1,
        "oeng" to 1,
        "oong" to 1,
        "oăng" to 1,
        "uang" to 1,
        "uech" to 1,
        "uenh" to 1,
        "uong" to 1,
        "uych" to 1,
        "uyen" to 2,
        "uyet" to 2,
        "uynh" to 1,
        "uyên" to 2,
        "uyêt" to 2,
        "uâng" to 1,
        "uêch" to 1,
        "uênh" to 1,
        "uông" to 1,
        "yeng" to 1,
        "yêng" to 1,
        "ương" to 1
    )

    private val VALID_RIME_PREFIX_SET: Set<String> = hashSetOf<String>().apply {
        for (key in TONE_POSITION_MAP.keys) {
            for (len in 1..key.length) {
                add(key.substring(0, len))
            }
        }
    }

    /**
     * Determine tone mark index within rime after preprocessing qu/gi onset.
     */
    fun findTonePosition(onset: String, rime: String, oldTonePlacement: Boolean = false): Int? {
        val onsetLower = onset.lowercase()
        var effectiveRime = rime.lowercase()
        var offset = 0

        // Preprocessing:
        // qu + u + following vowel -> strip u
        if ((onsetLower.endsWith("q") || onsetLower == "qu") && effectiveRime.startsWith("u") && effectiveRime.length > 1) {
            effectiveRime = effectiveRime.substring(1)
            offset = 1
        } else if ((onsetLower.endsWith("g") || onsetLower == "gi") && effectiveRime.startsWith("i") && effectiveRime.length > 1) {
            // gi + i + following vowel -> strip i
            effectiveRime = effectiveRime.substring(1)
            offset = 1
        }

        if (oldTonePlacement) {
            when (effectiveRime) {
                "oa" -> return 0 + offset
                "oe" -> return 0 + offset
                "uy" -> return 0 + offset
            }
        }

        val idx = TONE_POSITION_MAP[effectiveRime] ?: return null
        return idx + offset
    }

    /**
     * Check if candidate is a key or prefix of at least one key in TONE_POSITION_MAP.
     */
    fun isValidNucleusPrefix(candidate: String): Boolean {
        if (candidate.isEmpty()) return true
        val lower = candidate.lowercase()
        return VALID_RIME_PREFIX_SET.contains(lower)
    }

    /**
     * Apply tone mark to a single vowel character using TONE_COMBINE_MAP.
     */
    fun applyToneToChar(c: Char, tone: Tone): Char {
        if (tone == Tone.NONE) return VietnameseUnicode.stripTone(c)
        val isUpper = c.isUpperCase()
        val baseChar = VietnameseUnicode.stripTone(c).lowercaseChar()
        val mapped = TONE_COMBINE_MAP[baseChar]?.get(tone) ?: return c
        return if (isUpper) mapped.uppercaseChar() else mapped
    }
}
