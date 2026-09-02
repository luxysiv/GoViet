package com.goviet.keyboard.engine

import com.goviet.keyboard.engine.VietnameseComposer.LastToggle
import com.goviet.keyboard.engine.VietnameseComposer.TargetType

/**
 * VietnameseSpellingGuide:
 * Original data-driven Telex spelling core.
 *
 * Telex mutations are described as DATA (a fold table + a priority pattern chain)
 * and driven by ONE generic fold/unfold kernel, so the composer consults this guide
 * instead of hand-writing one if/else branch per transform letter.
 *
 * Why this differs from classic engines:
 *   - Folding is PER-TILE: the nucleus is a sequence of plain letters, each
 *     carrying an independent fold (a->â/ă, e->ê, o->ô/ơ, u->ư, d->đ). A tone is a
 *     separate axis applied only to the fold's target tile.

 *   - The "w" horn key is a PRIORITY CHAIN of context pattern rules evaluated in
 *     order, then a generic single-tile fold — one uniform kernel for all keys.
 */
object VietnameseSpellingGuide {

    // ============================================================
    // DATA: FOLD TABLE
    // ============================================================
    data class FoldRule(
        val key: Char,
        val fromA: Char,     // primary plain tile
        val fromB: Char,     // secondary plain tile (0 = none)
        val to: Char,        // folded display tile
        val require: CharArray? = null,
        val exclude: CharArray? = null,
        val excludeOnset: String? = null
    )

    private val FOLD_RULES = arrayOf(
        // w horn (priority): o->ơ, u->ư (not after q), a->ă
        FoldRule('w', 'o', 'ô', 'ơ', require = charArrayOf('o', 'ô'), exclude = charArrayOf('ơ')),
        FoldRule('w', 'u', '\u0000', 'ư', require = charArrayOf('u'), exclude = charArrayOf('ư'), excludeOnset = "q"),
        FoldRule('w', 'a', 'â', 'ă', require = charArrayOf('a', 'â'), exclude = charArrayOf('ă')),
        // o: o/ơ -> ô
        FoldRule('o', 'o', 'ơ', 'ô'),
        // e: e -> ê
        FoldRule('e', 'e', '\u0000', 'ê'),
        // a: a/ă -> â
        FoldRule('a', 'a', 'ă', 'â')
    )

    private val EMPTY_RULES = arrayOf<FoldRule>()
    private val W_RULES = arrayOf(FOLD_RULES[0], FOLD_RULES[1], FOLD_RULES[2])
    private val O_RULES = arrayOf(FOLD_RULES[3])
    private val E_RULES = arrayOf(FOLD_RULES[4])
    private val A_RULES = arrayOf(FOLD_RULES[5])

    fun foldRulesFor(key: Char): Array<FoldRule> = when (key) {
        'w' -> W_RULES
        'o' -> O_RULES
        'e' -> E_RULES
        'a' -> A_RULES
        else -> EMPTY_RULES
    }

    /** Plain letter that a folded display letter unfolds back to. */
    fun plainOf(folded: Char): Char = when (folded) {
        'ê' -> 'e'; 'ô' -> 'o'; 'ơ' -> 'o'; 'â' -> 'a'; 'ă' -> 'a'; 'ư' -> 'u'; 'đ' -> 'd'
        else -> folded
    }

    // ============================================================
    // SINGLE-TILE FOLD KERNEL
    // ============================================================
    class FoldResult {
        var nucleus: String = ""
        var hadCharsAfter: Boolean = false
    }

    /**
     * Apply the first applicable rule in [rules] to [nucleus].
     * Returns true and fills [out] on success; false otherwise.
     */
    fun foldSingle(
        nucleus: String,
        rules: Array<FoldRule>,
        withCoda: String,
        onset: String,
        out: FoldResult
    ): Boolean {
        if (nucleus.isEmpty()) return false
        val pLower = nucleus.lowercase()
        val onsetLower = onset.lowercase()
        for (rule in rules) {
            val require = rule.require ?: charArrayOf(rule.fromA, rule.fromB)
            var has = false
            for (c in require) if (c != '\u0000' && pLower.indexOf(c) >= 0) { has = true; break }
            if (!has) continue
            if (rule.exclude != null) {
                var blocked = false
                for (c in rule.exclude) if (pLower.indexOf(c) >= 0) { blocked = true; break }
                if (blocked) continue
            }
            if (rule.excludeOnset != null && onsetLower == rule.excludeOnset) continue

            var idx = -1
            for (i in 0 until nucleus.length) {
                val c = nucleus[i].lowercaseChar()
                if (c == rule.fromA || (rule.fromB != '\u0000' && c == rule.fromB)) { idx = i; break }
            }
            if (idx == -1) continue

            val isUpper = nucleus[idx].isUpperCase()
            val replacement = if (isUpper) rule.to.uppercaseChar() else rule.to
            val newNucleus = replaceAt(nucleus, idx, replacement)
            val newRime = newNucleus + withCoda
            if (!VietnameseFiniteStateTable.isValidPrefix(newRime)) return false
            out.nucleus = newNucleus
            out.hadCharsAfter = withCoda.isNotEmpty() || (idx < nucleus.length - 1)
            return true
        }
        return false
    }

    // ============================================================
    // UNFOLD KERNEL (double-consume at cursor)
    // ============================================================

    /**
     * Unfold a previously applied fold back to its plain letter and produce the
     * literal that must be appended (user's double-consume rule).
     * On success returns Pair(newNucleus, literalTail) where literalTail is the
     * character(s) appended after the unfold (may be the literal key to preserve).
     */
    class UnfoldResult {
        var nucleus: String = ""
        var tail: Char = '\u0000'
        var foldedIndex: Int = -1
    }

    /**
     * Unfold a previously applied fold back to its plain letter.
     * The composer uses [UnfoldResult.foldedIndex] to decide whether the literal
     * [UnfoldResult.tail] appends to the nucleus (nothing after the fold) or to the
     * raw suffix (characters follow), implementing the user's double-consume rule.
     */
    fun unfold(
        nucleus: String,
        targetType: TargetType,
        isUpper: Boolean,
        out: UnfoldResult
    ): Boolean {
        val target = when (targetType) {
            TargetType.E_NUCLEUS -> 'ê'
            TargetType.O_NUCLEUS -> 'ô'
            TargetType.A_NUCLEUS -> 'â'
            TargetType.W_NUCLEUS -> {
                val pLower = nucleus.lowercase()
                when {
                    pLower.contains("ươ") -> 'ơ'
                    pLower.contains('ơ') -> 'ơ'
                    pLower.contains('ư') -> 'ư'
                    pLower.contains('ă') -> 'ă'
                    else -> return false
                }
            }
            else -> return false
        }
        var foundIdx = -1
        var foundChar = '\u0000'
        for (i in 0 until nucleus.length) {
            if (nucleus[i].lowercaseChar() == target) { foundIdx = i; foundChar = nucleus[i]; break }
        }
        if (foundIdx == -1) return false
        val isCharUpper = foundChar.isUpperCase()
        val replacement = if (isCharUpper) plainOf(target).uppercaseChar() else plainOf(target)
        var newNucleus = replaceAt(nucleus, foundIdx, replacement)
        if (targetType == TargetType.W_NUCLEUS && nucleus.lowercase().contains("ươ")) {
            newNucleus = newNucleus.replace("Ư", "U").replace("ư", "u")
        }
        val extraChar = if (isUpper) keyCharUpper(targetType) else keyChar(targetType)
        out.nucleus = newNucleus
        out.tail = extraChar
        out.foldedIndex = foundIdx
        return true
    }


    // ============================================================
    // VOWEL COMBINATION TABLE — vowel+vowel nucleus expansion
    // ============================================================
    /**
     * When a vowel character is typed after an existing nucleus, these rules
     * determine the resulting compound nucleus. Ordered by specificity.
     *
     * Key insight from Vietnamese phonology:
     *   ư + o → ươ    (compound)
     *   ư + a → ưa    (compound)
     *   uơ + i → ươi  (offglide)
     *   uơ + u → ươu  (offglide)
     */
    private data class VowelComboRule(
        val nucleusLower: String,
        val char: Char,
        val resultTemplate: String  // 'uppercase' means use char's case for 2nd letter
    )

    private val VOWEL_COMBINATION_RULES = arrayOf(
        VowelComboRule("ư", 'o', "ươ"),   // ươ
        VowelComboRule("ư", 'a', "ưa"),    // ưa
        VowelComboRule("uơ", 'i', "ươi"),  // ươi
        VowelComboRule("uơ", 'u', "ươu"),  // ươu
    )

    /**
     * Lookup vowel combination: nucleus + char → expanded nucleus.
     * Returns null if no special combination applies.
     */
    fun lookupVowelCombination(nucleus: String, char: Char): String? {
        val nLower = nucleus.lowercase()
        val cLower = char.lowercaseChar()
        for (rule in VOWEL_COMBINATION_RULES) {
            if (nLower == rule.nucleusLower && cLower == rule.char) {
                // Build result preserving casing from original nucleus + new char
                // Template is always lowercase; apply case from source chars
                val result = rule.resultTemplate
                val sb = StringBuilder(result.length)
                // Track whether source chars are uppercase
                val nucleusUpper = nucleus.isNotEmpty() && nucleus[0].isUpperCase()
                val charUpper = char.isUpperCase()
                for (i in result.indices) {
                    val ch = result[i]
                    // First char inherits nucleus casing, rest inherit newChar casing
                    val makeUpper = if (i == 0) nucleusUpper else charUpper
                    sb.append(if (makeUpper) ch.uppercaseChar() else ch)
                }
                return sb.toString()
            }
        }
        return null
    }

    // ============================================================
    // ONSET PROMOTION TABLE — gi/qu prefix handling
    // ============================================================
    /**
     * When a vowel is typed after nucleus is a single letter that forms
     * a compound onset prefix with the current onset consonant:
     *   g + i + V → gi|V  (gi becomes onset, V becomes nucleus)
     *   q + u + V → qu|V  (qu becomes onset, V becomes nucleus)
     */
    private val ONSET_PROMOTIONS = mapOf(
        "g" to "gi",
        "q" to "qu"
    )

    /**
     * Check if onset+nucleus should be promoted to a compound onset prefix.
     * Returns the new onset string if promotion applies, null otherwise.
     */
    fun lookupOnsetPromotion(onset: String, nucleus: String): String? {
        val oLower = onset.lowercase()
        val nLower = nucleus.lowercase()
        return ONSET_PROMOTIONS[oLower]?.takeIf { nLower == it.drop(oLower.length) }
    }

    // ============================================================
    // W PRIORITY CHAIN — declarative transformation rules
    // ============================================================
    /**
     * Each W rule matches a pattern in the nucleus and transforms it.
     * Evaluated in order — first match wins.
     */
    private data class WPatternRule(
        val pattern: String,           // substring to match in nucleus (lowercase)
        val transform: (String, String) -> Pair<String, LastToggle?>?  // (nucleus, coda) → result
    )

    private val W_PATTERN_CHAIN = arrayOf(
        // uo/uơ → uơ (open) or ươ (with coda)
        WPatternRule("uo") { nucleus, coda ->
            val hasCoda = coda.isNotEmpty() || nucleus.lowercase() in setOf("uoi", "uou")
            val transformed = buildUoPair(nucleus[0], nucleus[1], hornU = hasCoda)
            val newNucleus = nucleus.replaceRange(0, 2, transformed)
            val newRime = newNucleus + coda
            if (VietnameseFiniteStateTable.isValidPrefix(newRime)) {
                Pair(newNucleus, LastToggle('w', TargetType.W_NUCLEUS, hasCoda))
            } else null
        },
        // ươ already exists → no-op
        WPatternRule("ươ") { nucleus, _ ->
            Pair(nucleus, null)
        },
        // ua → ưa
        WPatternRule("ua") { nucleus, coda ->
            val uStr = if (nucleus[0].isUpperCase()) "Ư" else "ư"
            val aStr = if (nucleus.length > 1 && nucleus[1].isUpperCase()) "A" else "a"
            val newNucleus = nucleus.replaceRange(0, 2, uStr + aStr)
            val newRime = newNucleus + coda
            if (VietnameseFiniteStateTable.isValidPrefix(newRime)) {
                Pair(newNucleus, LastToggle('w', TargetType.W_NUCLEUS, coda.isNotEmpty()))
            } else null
        },
        // oa → oă
        WPatternRule("oa") { nucleus, coda ->
            val oStr = if (nucleus[0].isUpperCase()) "O" else "o"
            val aStr = if (nucleus.length > 1 && nucleus[1].isUpperCase()) "Ă" else "ă"
            val newNucleus = nucleus.replaceRange(0, 2, oStr + aStr)
            val newRime = newNucleus + coda
            if (VietnameseFiniteStateTable.isValidPrefix(newRime)) {
                Pair(newNucleus, LastToggle('w', TargetType.W_NUCLEUS, coda.isNotEmpty()))
            } else null
        },
    )

    // ============================================================
    // W HORN KERNEL
    // ============================================================
    fun applyW(
        nucleus: String,
        withCoda: String,
        onset: String
    ): Pair<String, LastToggle?>? {
        val pLower = nucleus.lowercase()

        // Priority chain: pattern rules evaluated in order, first match wins
        for (rule in W_PATTERN_CHAIN) {
            if (pLower.contains(rule.pattern)) {
                val result = rule.transform(nucleus, withCoda)
                if (result != null) return result
                // Pattern matched but transform failed validation → fall through
            }
        }

        // Generic single-tile fold fallback: o→ơ, u→ư, a→ă
        val res = FoldResult()
        if (foldSingle(nucleus, foldRulesFor('w'), withCoda, onset, res)) {
            return Pair(res.nucleus, LastToggle('w', TargetType.W_NUCLEUS, res.hadCharsAfter))
        }
        return null
    }

    /** Build the uơ/ươ pair preserving casing (hornU controls the first letter). */
    fun buildUoPair(uChar: Char, oChar: Char, hornU: Boolean): String {
        val uStr = if (hornU) {
            if (uChar.isUpperCase()) "Ư" else "ư"
        } else {
            if (uChar.isUpperCase()) "U" else "u"
        }
        val oStr = if (oChar.isUpperCase()) "Ơ" else "ơ"
        return uStr + oStr
    }

    private fun replaceAt(str: String, idx: Int, replacement: Char): String {
        val arr = str.toCharArray()
        arr[idx] = replacement
        return String(arr)
    }

    private fun keyChar(t: TargetType): Char = when (t) {
        TargetType.E_NUCLEUS -> 'e'
        TargetType.O_NUCLEUS -> 'o'
        TargetType.A_NUCLEUS -> 'a'
        TargetType.W_NUCLEUS -> 'w'
        TargetType.W_SOLO -> 'w'
        TargetType.D_ONSET -> 'd'
    }

    private fun keyCharUpper(t: TargetType): Char = keyChar(t).uppercaseChar()

    // ============================================================
    // TONE PLACEMENT — data-driven nucleus-position table
    // ============================================================

    /**
     * Maps each rime (nucleus+coda) to the character index where the tone mark lands.
     *
     * Index semantics:
     *  - pos 0 → tone on the FIRST vowel (head vowel)
     *  - pos 1 → tone on the SECOND vowel (main/rhyming vowel)
     *  - pos 2 → tone on the THIRD vowel
     *
     * Single vowels always get pos 0.
     * Compound nuclei (iê, uô, ươ, ...) get pos 1 (the main vowel is the 2nd char).
     * Triple nuclei (uye, uyê) get pos 2.
     * STYLE_VARIANT_RIMES (oa, oă, oe, ue, uy) differ between LEGACY and MODERN.
     */
    private val STYLE_VARIANT_RIMES = setOf("oa", "oă", "oe", "ue", "uy")

    /**
     * All bare Vietnamese nuclei (no coda). Used to distinguish bare nuclei from
     * coda forms (e.g. "oa" is a bare nucleus, "oan" is a coda form).
     */
    private val BARE_NUCLEI = setOf(
        "a", "ă", "â", "e", "ê", "i", "o", "ô", "ơ", "u", "ư", "y",
        "ua", "ưa", "ia",
        "uâ", "uê", "uô", "uơ", "ươ",
        "ie", "iê", "ye", "yê", "oo", "uo", "oa", "oă", "oe", "ue", "uy",
        "ieu", "yêu", "yeu",
        "uôi", "uơi", "uou", "uya", "uyu", "ươi", "ươu",
        "oai", "oao", "oay", "oeo", "uau", "uay", "uâu", "uây",
        "ueu", "uêu", "uye", "uyê"
    )

    /**
     * Computes tone position for a bare nucleus (no coda) using phonological rules.
     *
     * Rules (derived from Vietnamese phonology):
     *  - Single vowel: pos 0 (tone on the vowel itself)
     *  - Head-vowel nuclei (ua, ưa, ia): pos 0
     *  - Compound/triple nuclei: pos 1 (rhyming vowel is 2nd char)
     *  - Triple nuclei uye/uyê: pos 2
     *
     * This replaces the old NUCLEUS_POSITIONS lookup table with a computed function.
     */
    private fun computeTonePos(nucleus: String): Int = when {
        nucleus.length == 1 -> 0
        nucleus == "ua" || nucleus == "ưa" || nucleus == "ia" -> 0
        nucleus == "uye" || nucleus == "uyê" -> 2
        else -> 1
    }

    /**
     * Determines the character index within [rime] where the tone mark lands.
     * This is the ONE function all tone logic calls.
     *
     * [rime] = nucleus + coda (e.g. "oan" for hoàn, "iêng" for tiếng).
     * The caller must concatenate nucleus and coda before calling this function.
     *
     * Lookup order:
     *  1. STYLE_VARIANT_RIMES → style-dependent (LEGACY=0, MODERN=1)
     *  2. computeTonePos() for bare nuclei (whitelist-checked)
     *  3. Trie fallback (for coda forms not covered by the above)
     */
    fun determineTonePosition(rime: String, onset: String, placement: TonePlacement): Int {
        if (rime.isEmpty()) return 0

        val lc = rime.lowercase()

        // 1. Style-variant bare rimes (coda forms handled by trie)
        if (lc in STYLE_VARIANT_RIMES) {
            return if (placement == TonePlacement.LEGACY) 0 else 1
        }

        // 2. Computed tone position for bare nuclei (whitelist-checked to avoid
        //    misrouting 3-char coda forms like "ang", "anh", "ong" to computeTonePos)
        if (lc in BARE_NUCLEI) {
            return computeTonePos(lc)
        }

        // 3. Fallback: trie handles coda forms (oan, iên, oang, etc.)
        val findOld = (placement == TonePlacement.LEGACY)
        return VietnameseFiniteStateTable.findTonePosition(onset, rime, findOld) ?: 0
    }
}
