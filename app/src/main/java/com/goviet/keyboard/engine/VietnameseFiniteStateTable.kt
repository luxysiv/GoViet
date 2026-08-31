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
                TRIE_TONE_POS_NEW[child] = 0
                TRIE_TONE_POS_OLD[child] = 0
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
     * Build the Trie containing all 211+ standard and raw Vietnamese rimes.
     */
    private fun buildRimeTrie() {
        // Single vowels
        insertRime("a", 0, 0, canTakeCoda = true)
        insertRime("e", 0, 0, canTakeCoda = true)
        insertRime("i", 0, 0, canTakeCoda = true)
        insertRime("o", 0, 0, canTakeCoda = true)
        insertRime("u", 0, 0, canTakeCoda = true)
        insertRime("y", 0, 0, canTakeCoda = true)
        insertRime("ê", 0, 0, canTakeCoda = true)
        insertRime("ô", 0, 0, canTakeCoda = true)
        insertRime("ơ", 0, 0, canTakeCoda = true)
        insertRime("ư", 0, 0, canTakeCoda = true)

        // 2-vowel open diphthongs (with old vs new tone placement differentiation)
        insertRime("oa", 1, 0, canTakeCoda = true)
        insertRime("oă", 1, 0, canTakeCoda = true)
        insertRime("oe", 1, 0, canTakeCoda = true)
        insertRime("uy", 1, 0, canTakeCoda = true)
        insertRime("ua", 0, 0, canTakeCoda = false)
        insertRime("uâ", 1, 1, canTakeCoda = true)
        insertRime("ue", 1, 0, canTakeCoda = true)
        insertRime("uê", 1, 1, canTakeCoda = true)
        insertRime("uô", 1, 1, canTakeCoda = true)
        insertRime("uơ", 1, 1, canTakeCoda = false)
        insertRime("ưa", 0, 0, canTakeCoda = false)
        insertRime("ươ", 1, 1, canTakeCoda = true)
        insertRime("ia", 0, 0, canTakeCoda = false)
        insertRime("ie", 1, 1, canTakeCoda = true)
        insertRime("iê", 1, 1, canTakeCoda = true)
        insertRime("ye", 1, 1, canTakeCoda = true)
        insertRime("yê", 1, 1, canTakeCoda = true)
        insertRime("oo", 1, 1, canTakeCoda = true)
        insertRime("uo", 1, 1, canTakeCoda = true)

        // Offglide rimes (vowel + offglide i, y, u, o)
        insertRime("ai", 0, 0)
        insertRime("ao", 0, 0)
        insertRime("au", 0, 0)
        insertRime("ay", 0, 0)
        insertRime("âu", 0, 0)
        insertRime("ây", 0, 0)
        insertRime("eo", 0, 0)
        insertRime("eu", 0, 0)
        insertRime("êu", 0, 0)
        insertRime("iu", 0, 0)
        insertRime("ieu", 1, 1)
        insertRime("iêu", 1, 1)
        insertRime("oi", 0, 0)
        insertRime("ôi", 0, 0)
        insertRime("ơi", 0, 0)
        insertRime("ui", 0, 0)
        insertRime("uu", 0, 0)
        insertRime("ưu", 0, 0)
        insertRime("ưi", 0, 0)
        insertRime("oai", 1, 1)
        insertRime("oao", 1, 1)
        insertRime("oay", 1, 1)
        insertRime("oeo", 1, 1)
        insertRime("uau", 1, 1)
        insertRime("uay", 1, 1)
        insertRime("uâu", 1, 1)
        insertRime("uây", 1, 1)
        insertRime("ueu", 1, 1)
        insertRime("uêu", 1, 1)
        insertRime("uoi", 1, 1)
        insertRime("uôi", 1, 1)
        insertRime("uơi", 1, 1)
        insertRime("uou", 1, 1)
        insertRime("uya", 1, 1)
        insertRime("uyu", 1, 1)
        insertRime("uye", 2, 2, canTakeCoda = true)
        insertRime("uyê", 2, 2, canTakeCoda = true)
        insertRime("yeu", 1, 1)
        insertRime("yêu", 1, 1)
        insertRime("ươi", 1, 1)
        insertRime("ươu", 1, 1)

        // Codas: -c, -ch, -p, -t (Stop codas -> Acute/Dot only)
        insertRime("ac", 0, 0, isStopCoda = true)
        insertRime("ap", 0, 0, isStopCoda = true)
        insertRime("at", 0, 0, isStopCoda = true)
        insertRime("ach", 0, 0, isStopCoda = true)
        insertRime("ăc", 0, 0, isStopCoda = true)
        insertRime("ăp", 0, 0, isStopCoda = true)
        insertRime("ăt", 0, 0, isStopCoda = true)
        insertRime("âc", 0, 0, isStopCoda = true)
        insertRime("âp", 0, 0, isStopCoda = true)
        insertRime("ât", 0, 0, isStopCoda = true)
        insertRime("ec", 0, 0, isStopCoda = true)
        insertRime("ep", 0, 0, isStopCoda = true)
        insertRime("et", 0, 0, isStopCoda = true)
        insertRime("ech", 0, 0, isStopCoda = true)
        insertRime("êc", 0, 0, isStopCoda = true)
        insertRime("êp", 0, 0, isStopCoda = true)
        insertRime("êt", 0, 0, isStopCoda = true)
        insertRime("êch", 0, 0, isStopCoda = true)
        insertRime("ic", 0, 0, isStopCoda = true)
        insertRime("ip", 0, 0, isStopCoda = true)
        insertRime("it", 0, 0, isStopCoda = true)
        insertRime("ich", 0, 0, isStopCoda = true)
        insertRime("oc", 0, 0, isStopCoda = true)
        insertRime("op", 0, 0, isStopCoda = true)
        insertRime("ot", 0, 0, isStopCoda = true)
        insertRime("ôc", 0, 0, isStopCoda = true)
        insertRime("ôp", 0, 0, isStopCoda = true)
        insertRime("ôt", 0, 0, isStopCoda = true)
        insertRime("ơp", 0, 0, isStopCoda = true)
        insertRime("ơt", 0, 0, isStopCoda = true)
        insertRime("uc", 0, 0, isStopCoda = true)
        insertRime("up", 0, 0, isStopCoda = true)
        insertRime("ut", 0, 0, isStopCoda = true)
        insertRime("ưc", 0, 0, isStopCoda = true)
        insertRime("ưp", 0, 0, isStopCoda = true)
        insertRime("ưt", 0, 0, isStopCoda = true)
        insertRime("yt", 0, 0, isStopCoda = true)
        insertRime("ych", 0, 0, isStopCoda = true)

        // Compound stop codas
        insertRime("iec", 1, 1, isStopCoda = true)
        insertRime("iep", 1, 1, isStopCoda = true)
        insertRime("iet", 1, 1, isStopCoda = true)
        insertRime("iêc", 1, 1, isStopCoda = true)
        insertRime("iêp", 1, 1, isStopCoda = true)
        insertRime("iêt", 1, 1, isStopCoda = true)
        insertRime("oac", 1, 1, isStopCoda = true)
        insertRime("oap", 1, 1, isStopCoda = true)
        insertRime("oat", 1, 1, isStopCoda = true)
        insertRime("oach", 1, 1, isStopCoda = true)
        insertRime("oec", 1, 1, isStopCoda = true)
        insertRime("oep", 1, 1, isStopCoda = true)
        insertRime("oet", 1, 1, isStopCoda = true)
        insertRime("ooc", 1, 1, isStopCoda = true)
        insertRime("oăc", 1, 1, isStopCoda = true)
        insertRime("oăp", 1, 1, isStopCoda = true)
        insertRime("oăt", 1, 1, isStopCoda = true)
        insertRime("uac", 1, 1, isStopCoda = true)
        insertRime("uap", 1, 1, isStopCoda = true)
        insertRime("uat", 1, 1, isStopCoda = true)
        insertRime("uâc", 1, 1, isStopCoda = true)
        insertRime("uâp", 1, 1, isStopCoda = true)
        insertRime("uât", 1, 1, isStopCoda = true)
        insertRime("uet", 1, 1, isStopCoda = true)
        insertRime("uêt", 1, 1, isStopCoda = true)
        insertRime("uech", 1, 1, isStopCoda = true)
        insertRime("uêch", 1, 1, isStopCoda = true)
        insertRime("uoc", 1, 1, isStopCoda = true)
        insertRime("uop", 1, 1, isStopCoda = true)
        insertRime("uot", 1, 1, isStopCoda = true)
        insertRime("uôc", 1, 1, isStopCoda = true)
        insertRime("uôp", 1, 1, isStopCoda = true)
        insertRime("uôt", 1, 1, isStopCoda = true)
        insertRime("uyp", 1, 1, isStopCoda = true)
        insertRime("uyt", 1, 1, isStopCoda = true)
        insertRime("uych", 1, 1, isStopCoda = true)
        insertRime("uyet", 2, 2, isStopCoda = true)
        insertRime("uyêt", 2, 2, isStopCoda = true)
        insertRime("yet", 1, 1, isStopCoda = true)
        insertRime("yêt", 1, 1, isStopCoda = true)
        insertRime("ươc", 1, 1, isStopCoda = true)
        insertRime("ươp", 1, 1, isStopCoda = true)
        insertRime("ươt", 1, 1, isStopCoda = true)

        // Codas: -m, -n, -ng, -nh (Nasal/Liquid codas -> all 5 tones allowed)
        insertRime("am", 0, 0)
        insertRime("an", 0, 0)
        insertRime("ang", 0, 0)
        insertRime("anh", 0, 0)
        insertRime("ăm", 0, 0)
        insertRime("ăn", 0, 0)
        insertRime("ăng", 0, 0)
        insertRime("âm", 0, 0)
        insertRime("ân", 0, 0)
        insertRime("âng", 0, 0)
        insertRime("em", 0, 0)
        insertRime("en", 0, 0)
        insertRime("eng", 0, 0)
        insertRime("enh", 0, 0)
        insertRime("êm", 0, 0)
        insertRime("ên", 0, 0)
        insertRime("êng", 0, 0)
        insertRime("ênh", 0, 0)
        insertRime("im", 0, 0)
        insertRime("in", 0, 0)
        insertRime("inh", 0, 0)
        insertRime("om", 0, 0)
        insertRime("on", 0, 0)
        insertRime("ong", 0, 0)
        insertRime("ôm", 0, 0)
        insertRime("ôn", 0, 0)
        insertRime("ông", 0, 0)
        insertRime("ơm", 0, 0)
        insertRime("ơn", 0, 0)
        insertRime("um", 0, 0)
        insertRime("un", 0, 0)
        insertRime("ung", 0, 0)
        insertRime("ưm", 0, 0)
        insertRime("ưn", 0, 0)
        insertRime("ưng", 0, 0)
        insertRime("yn", 0, 0)
        insertRime("ynh", 0, 0)

        // Compound nasal/liquid codas
        insertRime("iem", 1, 1)
        insertRime("ien", 1, 1)
        insertRime("ieng", 1, 1)
        insertRime("iêm", 1, 1)
        insertRime("iên", 1, 1)
        insertRime("iêng", 1, 1)
        insertRime("oam", 1, 1)
        insertRime("oan", 1, 1)
        insertRime("oang", 1, 1)
        insertRime("oanh", 1, 1)
        insertRime("oen", 1, 1)
        insertRime("oeng", 1, 1)
        insertRime("oăm", 1, 1)
        insertRime("oăn", 1, 1)
        insertRime("oăng", 1, 1)
        insertRime("oong", 1, 1)
        insertRime("uam", 1, 1)
        insertRime("uan", 1, 1)
        insertRime("uang", 1, 1)
        insertRime("uâm", 1, 1)
        insertRime("uân", 1, 1)
        insertRime("uâng", 1, 1)
        insertRime("uen", 1, 1)
        insertRime("uên", 1, 1)
        insertRime("uenh", 1, 1)
        insertRime("uênh", 1, 1)
        insertRime("uom", 1, 1)
        insertRime("uon", 1, 1)
        insertRime("uong", 1, 1)
        insertRime("uôm", 1, 1)
        insertRime("uôn", 1, 1)
        insertRime("uông", 1, 1)
        insertRime("uyn", 1, 1)
        insertRime("uynh", 1, 1)
        insertRime("uyen", 2, 2)
        insertRime("uyên", 2, 2)
        insertRime("yem", 1, 1)
        insertRime("yen", 1, 1)
        insertRime("yeng", 1, 1)
        insertRime("yêm", 1, 1)
        insertRime("yên", 1, 1)
        insertRime("yêng", 1, 1)
        insertRime("ươm", 1, 1)
        insertRime("ươn", 1, 1)
        insertRime("ương", 1, 1)
    }
}
