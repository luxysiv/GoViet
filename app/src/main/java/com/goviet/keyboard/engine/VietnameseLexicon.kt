package com.goviet.keyboard.engine

/**
 * VietnameseLexicon:
 * Single source of truth for Vietnamese phonological inventories shared across the engine:
 * - Base vowels (used during active Telex composition / parsing of tone-stripped text).
 * - Full vowel set including every accented variant (used for completed-word recognition).
 * - Ordered onset and coda lists for greedy lexical matching.
 *
 * Kept allocation-free and stateless; callers should rely on these instead of defining
 * their own duplicate vowel/consonant inventories.
 */
object VietnameseLexicon {

    private val BASE_VOWELS = setOf(
        'a', 'ă', 'â', 'e', 'ê', 'i', 'y', 'o', 'ô', 'ơ', 'u', 'ư'
    )

    private val VOWELS = setOf(
        'a', 'ă', 'â', 'e', 'ê', 'i', 'y', 'o', 'ô', 'ơ', 'u', 'ư',
        'á', 'ắ', 'ấ', 'é', 'ế', 'í', 'ý', 'ó', 'ố', 'ớ', 'ú', 'ứ',
        'à', 'ằ', 'ầ', 'è', 'ề', 'ì', 'ỳ', 'ò', 'ồ', 'ờ', 'ù', 'ừ',
        'ả', 'ẳ', 'ẩ', 'ẻ', 'ể', 'ỉ', 'ỷ', 'ỏ', 'ổ', 'ở', 'ủ', 'ử',
        'ã', 'ẵ', 'ẫ', 'ẽ', 'ễ', 'ĩ', 'ỹ', 'õ', 'ỗ', 'ỡ', 'ũ', 'ữ',
        'ạ', 'ặ', 'ậ', 'ẹ', 'ệ', 'ị', 'ỵ', 'ọ', 'ộ', 'ợ', 'ụ', 'ự'
    )

    /**
     * Consonant letters recognized while typing (superset of valid onsets:
     * includes f/j/q/w/x/z which are not valid Vietnamese onsets but are Telex
     * modifier or literal letters).
     */
    private val CONSONANTS = setOf(
        'b', 'c', 'd', 'đ', 'f', 'g', 'h', 'j', 'k', 'l', 'm', 'n',
        'p', 'q', 'r', 's', 't', 'v', 'w', 'x', 'z'
    )

    /**
     * Valid initial consonantal clusters (longest-first order for greedy matching).
     */
    val ONSETS = arrayOf(
        "ngh", "ng", "nh", "th", "tr", "ch", "ph", "kh", "gh", "gi", "qu",
        "b", "c", "d", "đ", "g", "h", "k", "l", "m", "n", "p", "r", "s", "t", "v", "x"
    )

    /**
     * Valid final consonantal clusters.
     */
    val CODAS = arrayOf("ng", "nh", "ch", "m", "p", "n", "t", "c")

    /**
     * True if [c] is one of the 12 base Vietnamese vowels (unaccented), used while
     * actively composing Telex input where accents are applied separately.
     */
    fun isBaseVowel(c: Char): Boolean = c.lowercaseChar() in BASE_VOWELS

    /**
     * True if [c] is any Vietnamese vowel including accented variants, used when
     * recognizing already-tone-marked completed words.
     */
    fun isVowel(c: Char): Boolean = c.lowercaseChar() in VOWELS

    fun isConsonant(c: Char): Boolean = c.lowercaseChar() in CONSONANTS
}
