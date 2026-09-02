package com.goviet.keyboard.engine

/**
 * VietnameseFiniteStateTable:
 * High-performance, Zero-Allocation Finite State Automaton (FSM) and Flat-Array Trie
 * for Vietnamese Syllable Parsing, Nucleus/Rime validation, Modifier Transitions, and Tone Placement.
 *
 * Architecture:
 * 1. Structural FSM: (EMPTY -> ONSET -> NUCLEUS -> CODA -> RAW_SUFFIX)
 * 2. Flat-Array Rime Trie: (O(1)/O(len) lookup, zero heap allocations, cache-friendly flat arrays)
 * 3. Unified Grammar & Phonology: (Single Source of Truth for Onset, Rime, Coda, Stop-coda, and Tones)
 */
object VietnameseFiniteStateTable {

    // ==========================================
    // RIME / NUCLEUS STATE BIT FLAGS
    // ==========================================
    const val FLAG_INVALID = 0
    const val FLAG_PREFIX = 1
    const val FLAG_COMPLETE = 2
    const val FLAG_PREFIX_AND_COMPLETE = FLAG_PREFIX or FLAG_COMPLETE
    const val FLAG_CAN_CODA = 4
    const val FLAG_CAN_TONE = 8
    const val FLAG_STOP_CODA = 16

    // ==========================================
    // STRUCTURAL STATES
    // ==========================================
    enum class StructuralState {
        EMPTY,
        ONSET,
        NUCLEUS,
        CODA,
        RAW_SUFFIX
    }

    // ==========================================
    // FSM ACTION CODES
    // ==========================================
    enum class FsmAction {
        NONE,
        START_ONSET,
        EXTEND_ONSET,
        START_NUCLEUS,
        EXTEND_NUCLEUS,
        TRANSFORM_NUCLEUS,
        TRANSFORM_D,
        APPLY_TONE,
        REMOVE_TONE,
        START_CODA,
        EXTEND_CODA,
        APPEND_LITERAL
    }

    // ==========================================
    // FLAT-ARRAY TRIE DATA STRUCTURE (0-ALLOC)
    // ==========================================
    private const val MAX_NODES = 512
    private var nodeCount = 1 // Node 0 is ROOT

    // Flat primitive arrays for Cache-friendly CPU execution
    private val TRIE_CHAR = CharArray(MAX_NODES)
    private val TRIE_FLAGS = ByteArray(MAX_NODES)
    private val TRIE_TONE_POS_NEW = ByteArray(MAX_NODES)
    private val TRIE_TONE_POS_OLD = ByteArray(MAX_NODES)
    private val TRIE_CHILD_HEAD = IntArray(MAX_NODES) { -1 }
    private val TRIE_NEXT_SIBLING = IntArray(MAX_NODES) { -1 }

    init {
        buildRimeTrie()
    }

    /**
     * Insert a rime into the Flat-Array Trie.
     */
    private fun insertRime(
        rime: String,
        newTonePos: Int,
        oldTonePos: Int = newTonePos,
        isStopCoda: Boolean = false,
        canTakeCoda: Boolean = false
    ) {
        var curr = 0
        for (i in rime.indices) {
            val ch = rime[i]
            var child = findChild(curr, ch)
            if (child == -1) {
                child = nodeCount++
                TRIE_CHAR[child] = ch
                TRIE_FLAGS[child] = FLAG_PREFIX.toByte()
                TRIE_TONE_POS_NEW[child] = if (curr != 0) TRIE_TONE_POS_NEW[curr] else 0
                TRIE_TONE_POS_OLD[child] = if (curr != 0) TRIE_TONE_POS_OLD[curr] else 0
                TRIE_NEXT_SIBLING[child] = TRIE_CHILD_HEAD[curr]
                TRIE_CHILD_HEAD[curr] = child
            }
            curr = child
            if (i < rime.length - 1) {
                TRIE_FLAGS[curr] = (TRIE_FLAGS[curr].toInt() or FLAG_PREFIX).toByte()
            }
        }

        var flags = TRIE_FLAGS[curr].toInt() or FLAG_COMPLETE or FLAG_CAN_TONE
        if (canTakeCoda) flags = flags or FLAG_CAN_CODA
        if (isStopCoda) flags = flags or FLAG_STOP_CODA
        TRIE_FLAGS[curr] = flags.toByte()
        TRIE_TONE_POS_NEW[curr] = newTonePos.toByte()
        TRIE_TONE_POS_OLD[curr] = oldTonePos.toByte()
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun findChild(parent: Int, ch: Char): Int {
        var child = TRIE_CHILD_HEAD[parent]
        while (child != -1) {
            if (TRIE_CHAR[child] == ch) return child
            child = TRIE_NEXT_SIBLING[child]
        }
        return -1
    }

    /**
     * Fast 0-Allocation Lookup of a rime/nucleus candidate in the Flat-Array Trie.
     * Returns nodeId or -1 if not found.
     */
    fun findNode(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Int {
        if (length == 0) return 0
        var curr = 0
        val end = start + length
        for (i in start until end) {
            val ch = candidate[i]
            val lower = toLower(ch)
            curr = findChild(curr, lower)
            if (curr == -1) return -1
        }
        return curr
    }

    /**
     * Check if candidate is a valid prefix (or complete rime) in Vietnamese.
     */
    fun isValidPrefix(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Boolean {
        if (length == 0) return true
        val node = findNode(candidate, start, length)
        return node != -1
    }

    /**
     * Check if candidate is a complete valid rime.
     */
    fun isCompleteRime(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Boolean {
        val node = findNode(candidate, start, length)
        if (node == -1) return false
        return (TRIE_FLAGS[node].toInt() and FLAG_COMPLETE) != 0
    }

    /**
     * Alias for isCompleteRime to support full phonological validation.
     */
    fun isValidRime(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Boolean =
        isCompleteRime(candidate, start, length)

    /**
     * Check if candidate can take a following coda consonant.
     */
    fun canTakeCoda(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Boolean {
        val node = findNode(candidate, start, length)
        if (node == -1) return false
        return (TRIE_FLAGS[node].toInt() and FLAG_CAN_CODA) != 0
    }

    /**
     * Check if candidate is a stop-coda rime (c, ch, p, t) restricting tones to Acute/Dot.
     */
    fun isStopCoda(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Boolean {
        val node = findNode(candidate, start, length)
        if (node == -1) {
            if (length == 0) return false
            val lastChar = toLower(candidate[start + length - 1])
            return lastChar == 'p' || lastChar == 't' || lastChar == 'c' ||
                    (length >= 2 && lastChar == 'h' && toLower(candidate[start + length - 2]) == 'c')
        }
        return (TRIE_FLAGS[node].toInt() and FLAG_STOP_CODA) != 0
    }

    /**
     * Lookup tone placement index in rime in O(len) without string allocations.
     */
    fun getTonePosition(candidate: CharSequence, oldTonePlacement: Boolean, start: Int = 0, length: Int = candidate.length - start): Int {
        val node = findNode(candidate, start, length)
        if (node == -1) return 0
        return if (oldTonePlacement) {
            TRIE_TONE_POS_OLD[node].toInt()
        } else {
            TRIE_TONE_POS_NEW[node].toInt()
        }
    }

    /**
     * Determine tone mark position with initial onset prefix preprocessing (e.g. qu, gi).
     */
    fun findTonePosition(onset: CharSequence, rime: CharSequence, oldTonePlacement: Boolean): Int? {
        val onsetLen = onset.length
        val rimeLen = rime.length
        if (rimeLen == 0) return null

        var rimeStart = 0
        var offset = 0

        // Zero-allocation preprocessing for onset prefix: qu / gi
        if (rimeLen > 1 && onsetLen > 0) {
            val rimeFirst = rime[0]
            val isRimeFirstU = rimeFirst == 'u' || rimeFirst == 'U'
            val isRimeFirstI = rimeFirst == 'i' || rimeFirst == 'I'

            val isQ = (onset[onsetLen - 1] == 'q' || onset[onsetLen - 1] == 'Q') ||
                    (onsetLen >= 2 && (onset[onsetLen - 2] == 'q' || onset[onsetLen - 2] == 'Q') && (onset[onsetLen - 1] == 'u' || onset[onsetLen - 1] == 'U'))
            val isG = (onset[onsetLen - 1] == 'g' || onset[onsetLen - 1] == 'G') ||
                    (onsetLen >= 2 && (onset[onsetLen - 2] == 'g' || onset[onsetLen - 2] == 'G') && (onset[onsetLen - 1] == 'i' || onset[onsetLen - 1] == 'I'))

            if (isRimeFirstU && isQ) {
                rimeStart = 1
                offset = 1
            } else if (isRimeFirstI && isG) {
                rimeStart = 1
                offset = 1
            }
        }

        val node = findNode(rime, rimeStart, rimeLen - rimeStart)
        if (node == -1) return null

        val basePos = if (oldTonePlacement) TRIE_TONE_POS_OLD[node].toInt() else TRIE_TONE_POS_NEW[node].toInt()
        return basePos + offset
    }

    // ==========================================
    // ONSET & CODA PHONOLOGICAL RULES (0-ALLOC)
    // ==========================================

    @Suppress("NOTHING_TO_INLINE")
    private inline fun toLower(c: Char): Char =
        if (c in 'A'..'Z') (c.code + 32).toChar() else c.lowercaseChar()

    fun isValidOnset(onset: CharSequence, start: Int = 0, length: Int = onset.length - start): Boolean {
        if (length == 0) return true
        if (length == 1) {
            val c = toLower(onset[start])
            return when (c) {
                'b', 'c', 'd', 'đ', 'g', 'h', 'k', 'l', 'm', 'n', 'p', 'r', 's', 't', 'v', 'x' -> true
                else -> false
            }
        }
        if (length == 2) {
            val c0 = toLower(onset[start])
            val c1 = toLower(onset[start + 1])
            return when (c0) {
                'c' -> c1 == 'h'
                'g' -> c1 == 'h' || c1 == 'i'
                'k' -> c1 == 'h'
                'n' -> c1 == 'h' || c1 == 'g'
                'p' -> c1 == 'h'
                'q' -> c1 == 'u'
                't' -> c1 == 'h' || c1 == 'r'
                else -> false
            }
        }
        if (length == 3) {
            val c0 = toLower(onset[start])
            val c1 = toLower(onset[start + 1])
            val c2 = toLower(onset[start + 2])
            return c0 == 'n' && c1 == 'g' && c2 == 'h'
        }
        return false
    }

    fun isValidCoda(coda: CharSequence, start: Int = 0, length: Int = coda.length - start): Boolean {
        if (length == 0) return true
        if (length == 1) {
            val c = toLower(coda[start])
            return c == 'm' || c == 'p' || c == 'n' || c == 't' || c == 'c'
        }
        if (length == 2) {
            val c0 = toLower(coda[start])
            val c1 = toLower(coda[start + 1])
            return (c0 == 'n' && (c1 == 'g' || c1 == 'h')) || (c0 == 'c' && c1 == 'h')
        }
        return false
    }

    /**
     * Validate whether a tone is grammatically allowed on a specific rime (0 Alloc).
     */
    fun isValidToneForRime(rime: CharSequence, tone: Tone, start: Int = 0, length: Int = rime.length - start): Boolean {
        if (tone == Tone.NONE) return true
        if (length == 0) return true
        if (isStopCoda(rime, start, length)) {
            return tone == Tone.ACUTE || tone == Tone.DOT
        }
        return true
    }

    /**
     * Check if a complete word is phonologically valid in Vietnamese.
     */
    fun isValidWord(word: String): Boolean {
        if (word.isEmpty()) return false
        val stripped = VietnameseUnicode.stripToneFromWord(word)
        val len = stripped.length
        for (onsetLen in minOf(3, len) downTo 1) {
            if (isValidOnset(stripped, 0, onsetLen)) {
                if (isValidRime(stripped, onsetLen, len - onsetLen)) {
                    return true
                }
            }
        }
        return isValidRime(stripped, 0, len)
    }

    /**
     * Build the Trie containing all standard and raw Vietnamese rimes using data-driven combinations.
     */
    private fun buildRimeTrie() {
        val codasAll = arrayOf("c", "ch", "p", "t", "m", "n", "ng", "nh")
        val codasStandard = arrayOf("c", "p", "t", "m", "n", "ng")
        val codasDental = arrayOf("t", "ch", "n", "nh")
        val codasLabialDental = arrayOf("p", "t", "ch", "n", "nh")

        fun insertNucleusWithCodas(nucleus: String, codas: Array<String>, tonePos: Int) {
            for (coda in codas) {
                val isStop = coda == "c" || coda == "ch" || coda == "p" || coda == "t"
                insertRime(nucleus + coda, tonePos, tonePos, isStopCoda = isStop, canTakeCoda = false)
            }
        }

        // 1. Single vowels (Bare tone: 0, 0 | with Coda: 0, 0)
        insertRime("a", 0, 0, canTakeCoda = true); insertNucleusWithCodas("a", codasAll, 0)
        insertRime("ă", 0, 0, canTakeCoda = true); insertNucleusWithCodas("ă", codasStandard, 0)
        insertRime("â", 0, 0, canTakeCoda = true); insertNucleusWithCodas("â", codasStandard, 0)
        insertRime("e", 0, 0, canTakeCoda = true); insertNucleusWithCodas("e", codasAll, 0)
        insertRime("ê", 0, 0, canTakeCoda = true); insertNucleusWithCodas("ê", codasAll, 0)
        insertRime("i", 0, 0, canTakeCoda = true); insertNucleusWithCodas("i", codasAll, 0)
        insertRime("o", 0, 0, canTakeCoda = true); insertNucleusWithCodas("o", codasStandard, 0)
        insertRime("ô", 0, 0, canTakeCoda = true); insertNucleusWithCodas("ô", codasStandard, 0)
        insertRime("ơ", 0, 0, canTakeCoda = true); insertNucleusWithCodas("ơ", codasStandard, 0)
        insertRime("u", 0, 0, canTakeCoda = true); insertNucleusWithCodas("u", codasStandard, 0)
        insertRime("ư", 0, 0, canTakeCoda = true); insertNucleusWithCodas("ư", codasStandard, 0)
        insertRime("y", 0, 0, canTakeCoda = true); insertNucleusWithCodas("y", codasDental, 0)

        // 2. Open diphthongs with style variation when bare (Bare: new=1, old=0 | with Coda: 1, 1)
        insertRime("oa", 1, 0, canTakeCoda = true); insertNucleusWithCodas("oa", codasAll, 1)
        insertRime("oă", 1, 0, canTakeCoda = true); insertNucleusWithCodas("oă", codasStandard, 1)
        insertRime("oe", 1, 0, canTakeCoda = true); insertNucleusWithCodas("oe", codasStandard, 1)
        insertRime("ue", 1, 0, canTakeCoda = true); insertNucleusWithCodas("ue", codasAll, 1)
        insertRime("uy", 1, 0, canTakeCoda = true); insertNucleusWithCodas("uy", codasLabialDental, 1)

        // 3. Compound diphthongs (Tone: 1, 1 | with Coda: 1, 1)
        insertRime("uâ", 1, 1, canTakeCoda = true); insertNucleusWithCodas("uâ", codasStandard, 1)
        insertRime("uê", 1, 1, canTakeCoda = true); insertNucleusWithCodas("uê", codasDental, 1)
        insertRime("uô", 1, 1, canTakeCoda = true); insertNucleusWithCodas("uô", codasStandard, 1)
        insertRime("uo", 1, 1, canTakeCoda = true); insertNucleusWithCodas("uo", codasStandard, 1)
        insertRime("ua", 0, 0, canTakeCoda = true); insertNucleusWithCodas("ua", codasStandard, 1)
        insertRime("ưa", 0, 0, canTakeCoda = false)
        insertRime("uơ", 1, 1, canTakeCoda = false)
        insertRime("ươ", 1, 1, canTakeCoda = true); insertNucleusWithCodas("ươ", codasStandard, 1)
        insertRime("ia", 0, 0, canTakeCoda = false)
        insertRime("ie", 1, 1, canTakeCoda = true); insertNucleusWithCodas("ie", codasStandard, 1)
        insertRime("iê", 1, 1, canTakeCoda = true); insertNucleusWithCodas("iê", codasStandard, 1)
        insertRime("ye", 1, 1, canTakeCoda = true); insertNucleusWithCodas("ye", arrayOf("t", "m", "n", "ng"), 1)
        insertRime("yê", 1, 1, canTakeCoda = true); insertNucleusWithCodas("yê", arrayOf("t", "m", "n", "ng"), 1)
        insertRime("oo", 1, 1, canTakeCoda = true); insertNucleusWithCodas("oo", arrayOf("c", "n", "ng", "m", "p", "t"), 1)

        // 4. Triphthong nuclei (Tone: 2, 2 | with Coda: 2, 2)
        insertRime("uye", 2, 2, canTakeCoda = true); insertNucleusWithCodas("uye", codasAll, 2)
        insertRime("uyê", 2, 2, canTakeCoda = true); insertNucleusWithCodas("uyê", codasAll, 2)

        // 5. Offglides (Tone: 0, 0)
        val simpleOffglides = arrayOf(
            "ai", "ao", "au", "ay", "âu", "ây", "eo", "eu", "êu", "iu", "oi", "ôi", "ơi", "ui", "uu", "ưu", "ưi"
        )
        for (r in simpleOffglides) insertRime(r, 0, 0)

        // 6. Compound & Triphthong Offglides (Tone: 1, 1)
        val compoundOffglides = arrayOf(
            "ieu", "iêu", "yeu", "yêu", "uoi", "uôi", "uơi", "uou", "uya", "uyu", "ươi", "ươu",
            "oai", "oao", "oay", "oeo", "uau", "uay", "uâu", "uây", "ueu", "uêu"
        )
        for (r in compoundOffglides) insertRime(r, 1, 1)
    }
}
