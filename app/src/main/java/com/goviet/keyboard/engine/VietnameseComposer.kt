package com.goviet.keyboard.engine

/**
 * VietnameseComposer:
 * Full Telex transformation algorithm implementation:
 * - Target letter principle
 * - Buffer state re-derivation
 * - Asymmetric toggle / untoggle handling
 * - Cross-syllable d/đ transformation
 * - Auto completion and promotion of uơ / ươ
 * - Never unilaterally revert to raw text
 */
class VietnameseComposer(var options: EngineOptions = EngineOptions()) {

    enum class TargetType {
        D_ONSET,
        E_NUCLEUS,
        O_NUCLEUS,
        A_NUCLEUS,
        W_NUCLEUS,
        W_SOLO
    }

    data class LastToggle(
        val key: Char,
        val targetType: TargetType,
        val hadCharsAfter: Boolean
    )

    class SyllableState(
        var onset: String = "",
        var nucleus: String = "",       // P: vowel nucleus (including offglides i, y, u, o)
        var coda: String = "",          // True final consonant coda: c, t, n, p, m, ng, ch, nh
        var tone: Tone = Tone.NONE,
        var lastToggle: LastToggle? = null,
        var rawSuffix: String = ""      // Trailing invalid characters, preserved without revert
    ) {
        fun copy(): SyllableState = SyllableState(
            onset = onset,
            nucleus = nucleus,
            coda = coda,
            tone = tone,
            lastToggle = lastToggle,
            rawSuffix = rawSuffix
        )

        fun reset() {
            onset = ""
            nucleus = ""
            coda = ""
            tone = Tone.NONE
            lastToggle = null
            rawSuffix = ""
        }

        fun isEmpty(): Boolean = onset.isEmpty() && nucleus.isEmpty() && coda.isEmpty() && rawSuffix.isEmpty()

        fun hasTransformedLetter(): Boolean {
            val s = onset.lowercase() + nucleus.lowercase()
            return s.any { it in listOf('â', 'ă', 'ê', 'ô', 'ơ', 'ư', 'đ') }
        }

        fun toDisplayString(oldTonePlacement: Boolean = false): String {
            if (isEmpty()) return ""
            val rime = nucleus + coda
            if (tone == Tone.NONE || nucleus.isEmpty()) {
                return onset + nucleus + coda + rawSuffix
            }

            val pos = TonePositionMap.findTonePosition(onset, rime, oldTonePlacement)
            val tonedNucleus = if (pos != null && pos in 0 until nucleus.length) {
                val targetChar = nucleus[pos]
                val tonedChar = TonePositionMap.applyToneToChar(targetChar, tone)
                nucleus.substring(0, pos) + tonedChar + nucleus.substring(pos + 1)
            } else if (nucleus.isNotEmpty()) {
                val targetChar = nucleus[0]
                val tonedChar = TonePositionMap.applyToneToChar(targetChar, tone)
                tonedChar + nucleus.substring(1)
            } else {
                nucleus
            }

            return onset + tonedNucleus + coda + rawSuffix
        }
    }

    data class ComposerSnapshot(
        val key: Char,
        val state: SyllableState,
        val displayText: String
    )

    private var currentSyllable = SyllableState()
    private val keyHistory = mutableListOf<Char>()
    private val undoStack = ArrayDeque<ComposerSnapshot>()

    fun reset() {
        currentSyllable.reset()
        keyHistory.clear()
        undoStack.clear()
    }

    /**
     * Process an individual key press from keyboard.
     */
    fun process(c: Char): EngineResult {
        if (c.isWhitespace() || isSeparator(c)) {
            val committed = currentSyllable.toDisplayString(options.oldTonePlacement) + c
            reset()
            return EngineResult(text = committed, consumed = true, composing = false)
        }

        keyHistory.add(c)
        val success = applyKey(currentSyllable, c, isStaticReDerive = false)
        val displayText = currentSyllable.toDisplayString(options.oldTonePlacement)
        undoStack.addLast(ComposerSnapshot(c, currentSyllable.copy(), displayText))
        return EngineResult(text = displayText, consumed = success, composing = true)
    }

    /**
     * Process backspace using Undo Stack (State Snapshot).
     * Restores 100% exact previous syllable state without error-prone re-parsing.
     */
    fun backspace(): String {
        if (undoStack.isNotEmpty()) {
            undoStack.removeLast()
        }
        if (keyHistory.isNotEmpty()) {
            keyHistory.removeAt(keyHistory.size - 1)
        }

        if (undoStack.isNotEmpty()) {
            val previousSnapshot = undoStack.last()
            currentSyllable = previousSnapshot.state.copy()
            return previousSnapshot.displayText
        } else {
            currentSyllable.reset()
            return ""
        }
    }

    /**
     * Process raw character sequence independently (Stateless).
     * Must NOT mutate interactive session state (currentSyllable, keyHistory, undoStack).
     */
    fun processString(raw: String): String {
        if (raw.isEmpty()) return ""

        val sb = StringBuilder()
        val tempSyllable = SyllableState()

        for (c in raw) {
            if (c.isWhitespace() || isSeparator(c)) {
                sb.append(tempSyllable.toDisplayString(options.oldTonePlacement))
                sb.append(c)
                tempSyllable.reset()
            } else {
                applyKey(tempSyllable, c, isStaticReDerive = false)
            }
        }
        sb.append(tempSyllable.toDisplayString(options.oldTonePlacement))
        return sb.toString()
    }

    /**
     * Re-derive conservative variant for static strings to protect non-Vietnamese words like deepseek, keep, book.
     */
    fun reDerive(raw: String): String {
        if (raw.isEmpty()) return ""

        val sb = StringBuilder()
        val words = raw.split(" ")
        for (i in words.indices) {
            val word = words[i]
            sb.append(reDeriveWord(word))
            if (i < words.size - 1) {
                sb.append(" ")
            }
        }
        return sb.toString()
    }

    private fun reDeriveWord(word: String): String {
        if (word.isEmpty()) return ""
        val lower = word.lowercase()

        // Check signals for non-Vietnamese words
        if (isForeignWord(lower)) {
            return word
        }

        var tempSyllable = SyllableState()
        for (c in word) {
            applyKey(tempSyllable, c, isStaticReDerive = true)
        }
        val result = tempSyllable.toDisplayString(options.oldTonePlacement)
        return GoVietCharUtils.applyCasingFromRaw(result, word)
    }

    private fun isForeignWord(lower: String): Boolean {
        // Rule 1 & 2: Detect invalid consonant clusters
        val codas = listOf("ng", "nh", "ch", "m", "p", "n", "t", "c")
        for (coda in codas) {
            val idx = lower.indexOf(coda)
            if (idx >= 0 && idx + coda.length < lower.length) {
                val nextChar = lower[idx + coda.length]
                if (isConsonantChar(nextChar)) {
                    val cluster2 = "" + coda.last() + nextChar
                    if (cluster2 !in TonePositionMap.VALID_CONSONANT_CLUSTERS &&
                        coda != "n" && coda != "m") {
                        return true
                    }
                    if (coda == "p" || coda == "c" || coda == "t") {
                        return true
                    }
                }
            }
        }

        // Invalid Vietnamese trailing consonants (e.g. k, f, s, l, r, v, z, b, d, g, sh, ck...)
        val invalidEndings = listOf("k", "f", "l", "r", "v", "z", "b", "d", "g", "sh", "th", "ck")
        for (ending in invalidEndings) {
            if (lower.endsWith(ending) && !lower.endsWith("ch") && !lower.endsWith("nh")) {
                return true
            }
        }

        return false
    }

    /**
     * Apply a single character c to the syllable state.
     */
    private fun applyKey(state: SyllableState, c: Char, isStaticReDerive: Boolean): Boolean {
        val lower = c.lowercaseChar()
        val isUpper = c.isUpperCase()

        // Telex brackets shortcut: [ -> ư, ] -> ơ
        if (lower == '[') {
            state.lastToggle = null
            return applyBracketKey(state, 'ư', isUpper)
        } else if (lower == ']') {
            state.lastToggle = null
            return applyBracketKey(state, 'ơ', isUpper)
        }

        // If invalid rawSuffix already exists:
        if (state.rawSuffix.isNotEmpty()) {
            state.lastToggle = null
            state.rawSuffix += c
            return true
        }

        // STEP 0: Character 'd'
        if (lower == 'd') {
            return handleKeyD(state, c, isStaticReDerive)
        }

        // STEP 1: Tone marks (s, f, r, x, j, z)
        if (isToneKey(lower)) {
            val handled = handleToneKey(state, lower, isStaticReDerive)
            if (handled) return true
        }

        // STEP 2: Type-A vowel/consonant modifiers (e, o, a, w)
        if (isVowelModifierKey(lower)) {
            val handled = handleVowelModifierKey(state, lower, isUpper, isStaticReDerive)
            if (handled) return true
        }

        // STEP 3: Regular vowels (a, e, i, o, u, y, ă, â, ê, ô, ơ, ư)
        if (isVowelChar(lower)) {
            return handleVowelChar(state, c)
        }

        // STEP 4: Regular consonants (or other characters)
        if (isConsonantChar(lower)) {
            return handleConsonantChar(state, c)
        }

        // Other characters (digits, symbols): append to rawSuffix
        state.lastToggle = null
        state.rawSuffix += c
        return true
    }

    private fun applyBracketKey(state: SyllableState, targetVowel: Char, isUpper: Boolean): Boolean {
        val v = if (isUpper) targetVowel.uppercaseChar() else targetVowel
        if (state.nucleus.isEmpty()) {
            state.nucleus = v.toString()
        } else if (state.coda.isEmpty() && TonePositionMap.isValidNucleusPrefix(state.nucleus + v)) {
            state.nucleus += v
        } else {
            state.rawSuffix += v
        }
        return true
    }

    private fun handleKeyD(state: SyllableState, c: Char, isStaticReDerive: Boolean): Boolean {
        val isUpper = c.isUpperCase()

        // 1. Check UNTOGGLE: onset is 'đ'/'Đ' and was previously toggled by 'd'
        if (state.lastToggle?.key == 'd' && (state.onset.lowercase() == "đ")) {
            val dChar = if (state.onset[0].isUpperCase()) "D" else "d"
            state.onset = dChar
            val extraChar = if (isUpper) "D" else "d"
            if (state.nucleus.isEmpty() && state.coda.isEmpty() && state.rawSuffix.isEmpty()) {
                // dd + d -> dd
                state.onset = dChar + extraChar
            } else {
                // dad + d -> dad
                state.rawSuffix += extraChar
            }
            state.lastToggle = null
            return true
        }

        // 2. Check Onset has unmodified 'd'/'D' -> TOGGLE 'đ' / 'Đ'
        val onsetLower = state.onset.lowercase()
        if (onsetLower == "d") {
            val dChar = if (state.onset[0].isUpperCase()) "Đ" else "đ"
            state.onset = dChar
            val hadCharsAfter = state.nucleus.isNotEmpty() || state.coda.isNotEmpty()
            state.lastToggle = LastToggle(key = 'd', targetType = TargetType.D_ONSET, hadCharsAfter = hadCharsAfter)
            return true
        }

        // 3. If onset is empty and no nucleus/coda yet -> initialize onset = 'd'
        if (state.onset.isEmpty() && state.nucleus.isEmpty() && state.coda.isEmpty()) {
            state.onset = c.toString()
            state.lastToggle = null
            return true
        }

        // 4. If nucleus already exists -> 'd' is not a valid Vietnamese coda -> treat as raw suffix
        state.lastToggle = null
        state.rawSuffix += c
        return true
    }

    private fun handleToneKey(state: SyllableState, key: Char, isStaticReDerive: Boolean): Boolean {
        // Requires vowel nucleus P
        if (state.nucleus.isEmpty()) return false

        // Check valid coda
        if (state.coda.isNotEmpty() && !VietnameseGrammar.isValidCoda(state.coda)) {
            return false
        }

        val targetTone = Tone.fromKey(key) ?: return false

        // Toggle tone: if same tone exists -> remove tone (reset to NONE)
        if (state.tone == targetTone) {
            state.tone = Tone.NONE
            if (!state.hasTransformedLetter()) {
                // For plain letters without type-A transformations: as + s -> as
                state.rawSuffix += key
            }
            // If transformed vowels exist (e.g. tôi, tư, ư...): keep intact without inserting rawSuffix
            state.lastToggle = null
            return true
        } else {
            // Replace or assign new tone
            state.tone = targetTone
            state.lastToggle = null
            return true
        }
    }

    private fun handleVowelModifierKey(
        state: SyllableState,
        key: Char,
        isUpper: Boolean,
        isStaticReDerive: Boolean
    ): Boolean {
        // 1. Check UNTOGGLE
        if (state.lastToggle?.key == key) {
            val toggle = state.lastToggle!!
            val untoggled = untoggleVowelModifier(state, toggle, key, isUpper)
            if (untoggled) {
                state.lastToggle = null
                return true
            }
        }

        // In static re-derive mode, do not auto toggle aa, ee, uu
        if (isStaticReDerive && (key == 'a' || key == 'e' || key == 'u') && key != 'o') {
            return false
        }

        // 2. TOGGLE modifier key based on Target letter
        when (key) {
            'e' -> return toggleNucleusChar(state, 'e', 'ê', TargetType.E_NUCLEUS, 'e')
            'o' -> return toggleNucleusChar(state, 'o', 'ô', TargetType.O_NUCLEUS, 'o')
            'a' -> return toggleNucleusChar(state, 'a', 'â', TargetType.A_NUCLEUS, 'a')
            'w' -> return handleKeyW(state, isUpper)
        }

        return false
    }

    private fun toggleNucleusChar(
        state: SyllableState,
        fromChar: Char,
        toChar: Char,
        targetType: TargetType,
        key: Char
    ): Boolean {
        val p = state.nucleus
        if (p.isEmpty()) return false

        val idx = p.indexOfFirst { it.lowercaseChar() == fromChar }
        if (idx == -1) return false

        val isCharUpper = p[idx].isUpperCase()
        val replacement = if (isCharUpper) toChar.uppercaseChar() else toChar
        val newNucleus = p.substring(0, idx) + replacement + p.substring(idx + 1)

        // Safety check (Step 4)
        val newRime = newNucleus + state.coda
        if (!TonePositionMap.isValidNucleusPrefix(newRime)) {
            return false
        }

        val hadCharsAfter = state.coda.isNotEmpty() || (idx < p.length - 1)
        state.nucleus = newNucleus
        state.lastToggle = LastToggle(key = key, targetType = targetType, hadCharsAfter = hadCharsAfter)
        return true
    }

    private fun handleKeyW(state: SyllableState, isUpper: Boolean): Boolean {
        // Empty nucleus case: 'w' acts as standalone vowel 'ư' or literal 'w' when directW is enabled
        if (state.nucleus.isEmpty()) {
            if (options.directW) {
                val wChar = if (isUpper) "W" else "w"
                if (state.onset.isEmpty()) {
                    state.onset = wChar
                } else {
                    state.rawSuffix += wChar
                }
                state.lastToggle = null
                return true
            }
            val uChar = if (isUpper) 'Ư' else 'ư'
            state.nucleus = uChar.toString()
            state.lastToggle = LastToggle(key = 'w', targetType = TargetType.W_SOLO, hadCharsAfter = false)
            return true
        }

        val pLower = state.nucleus.lowercase()

        // 1. Pair "uo" / "uơ" / "uoi" / "uon" / "uong" / "uoc" / "uot" / "uop" / "uom" / "uou":
        if (pLower.contains("uo") || pLower.contains("uơ")) {
            val isUUpper = state.nucleus[0].isUpperCase()
            val isOUpper = if (state.nucleus.length > 1) state.nucleus[1].isUpperCase() else false

            // If open rime without final consonant and offglide -> uơ
            val hasCodaOrOffglide = state.coda.isNotEmpty() || pLower == "uoi" || pLower == "uou"
            val transformed = if (hasCodaOrOffglide) {
                val uStr = if (isUUpper) "Ư" else "ư"
                val oStr = if (isOUpper) "Ơ" else "ơ"
                uStr + oStr
            } else {
                val uStr = if (isUUpper) "U" else "u"
                val oStr = if (isOUpper) "Ơ" else "ơ"
                uStr + oStr
            }

            val newNucleus = state.nucleus.replaceRange(0, 2, transformed)
            val newRime = newNucleus + state.coda
            if (TonePositionMap.isValidNucleusPrefix(newRime)) {
                state.nucleus = newNucleus
                state.lastToggle = LastToggle(key = 'w', targetType = TargetType.W_NUCLEUS, hadCharsAfter = hasCodaOrOffglide)
                return true
            }
        }

        // 2. If already "ươ" -> 'w' does nothing further
        if (pLower.contains("ươ")) {
            return true
        }

        // 3. Pair "ua" -> "ưa" (e.g. mua -> mưa, chuaw -> chưa)
        if (pLower.contains("ua")) {
            val isUUpper = state.nucleus[0].isUpperCase()
            val isAUpper = if (state.nucleus.length > 1) state.nucleus[1].isUpperCase() else false
            val uStr = if (isUUpper) "Ư" else "ư"
            val aStr = if (isAUpper) "A" else "a"
            val newNucleus = state.nucleus.replaceRange(0, 2, uStr + aStr)
            val newRime = newNucleus + state.coda
            if (TonePositionMap.isValidNucleusPrefix(newRime)) {
                state.nucleus = newNucleus
                state.lastToggle = LastToggle(key = 'w', targetType = TargetType.W_NUCLEUS, hadCharsAfter = state.coda.isNotEmpty())
                return true
            }
        }

        // 4. Pair "oa" -> "oă" (e.g. hoa -> hoă, hoawc -> hoăc, hoacjw -> hoặc, toanw -> toăn)
        if (pLower.contains("oa")) {
            val isOUpper = state.nucleus[0].isUpperCase()
            val isAUpper = if (state.nucleus.length > 1) state.nucleus[1].isUpperCase() else false
            val oStr = if (isOUpper) "O" else "o"
            val aStr = if (isAUpper) "Ă" else "ă"
            val newNucleus = state.nucleus.replaceRange(0, 2, oStr + aStr)
            val newRime = newNucleus + state.coda
            if (TonePositionMap.isValidNucleusPrefix(newRime)) {
                state.nucleus = newNucleus
                state.lastToggle = LastToggle(key = 'w', targetType = TargetType.W_NUCLEUS, hadCharsAfter = state.coda.isNotEmpty())
                return true
            }
        }

        // 5. Target letter 'o' -> 'ơ' (e.g. oi -> ơi, moiw -> mơi)
        if (pLower.contains('o') && !pLower.contains('ô') && !pLower.contains('ơ')) {
            return toggleNucleusChar(state, 'o', 'ơ', TargetType.W_NUCLEUS, 'w')
        }

        // 6. Target letter 'u' -> 'ư' (e.g. u -> ư, ui -> ưi, dungw -> dưng)
        if (pLower.contains('u') && !pLower.contains('ư')) {
            return toggleNucleusChar(state, 'u', 'ư', TargetType.W_NUCLEUS, 'w')
        }

        // 7. Target letter 'a' -> 'ă' (e.g. a -> ă, banw -> băn, langw -> lăng)
        if (pLower.contains('a') && !pLower.contains('â') && !pLower.contains('ă')) {
            return toggleNucleusChar(state, 'a', 'ă', TargetType.W_NUCLEUS, 'w')
        }

        return false
    }

    private fun untoggleVowelModifier(
        state: SyllableState,
        toggle: LastToggle,
        key: Char,
        isUpper: Boolean
    ): Boolean {
        if (toggle.targetType == TargetType.W_SOLO) {
            // w -> ư; ww -> w
            val extraChar = if (isUpper) 'W' else 'w'
            state.nucleus = ""
            state.rawSuffix = extraChar.toString()
            return true
        }

        val (targetChar, plainChar) = when (toggle.targetType) {
            TargetType.E_NUCLEUS -> 'ê' to 'e'
            TargetType.O_NUCLEUS -> 'ô' to 'o'
            TargetType.A_NUCLEUS -> 'â' to 'a'
            TargetType.W_NUCLEUS -> {
                val pLower = state.nucleus.lowercase()
                if (pLower.contains("ươ")) {
                    'ơ' to 'o'
                } else if (pLower.contains('ơ')) {
                    'ơ' to 'o'
                } else if (pLower.contains('ư')) {
                    'ư' to 'u'
                } else if (pLower.contains('ă')) {
                    'ă' to 'a'
                } else {
                    return false
                }
            }
            else -> return false
        }

        val p = state.nucleus
        val idx = p.indexOfFirst { it.lowercaseChar() == targetChar }
        if (idx == -1) return false

        val isCharUpper = p[idx].isUpperCase()
        val replacement = if (isCharUpper) plainChar.uppercaseChar() else plainChar
        var newNucleus = p.substring(0, idx) + replacement + p.substring(idx + 1)
        if (toggle.targetType == TargetType.W_NUCLEUS && p.lowercase().contains("ươ")) {
            newNucleus = newNucleus.replace("Ư", "U").replace("ư", "u")
        }

        val extraChar = if (isUpper) key.uppercaseChar() else key

        // If no coda and no rawSuffix:
        // oo -> untoggle ô to "oo"
        // ee -> untoggle ê to "ee"
        // aa -> untoggle â to "aa"
        // uw -> untoggle ư to "uw"
        if (state.coda.isEmpty() && state.rawSuffix.isEmpty() && (idx == p.length - 1)) {
            state.nucleus = newNucleus + extraChar
        } else {
            // Asymmetric untoggle when following characters exist:
            // In-place fix and append literal at the trailing cursor position
            state.nucleus = newNucleus
            state.rawSuffix += extraChar
        }

        return true
    }

    private fun handleVowelChar(state: SyllableState, c: Char): Boolean {
        val lower = c.lowercaseChar()

        // Preprocess gi:
        // If onset is "g" and nucleus is "i", and user enters another vowel:
        // "gi" becomes onset, and new vowel becomes nucleus (e.g. gia -> onset "gi", nucleus "a")
        if (state.onset.lowercase() == "g" && state.nucleus.lowercase() == "i" && state.coda.isEmpty()) {
            state.onset = if (state.onset[0].isUpperCase()) "Gi" else "gi"
            state.nucleus = c.toString()
            state.lastToggle = null
            return true
        }

        // If nucleus has 'ư' and user types 'o'
        if (lower == 'o' && state.nucleus.lowercase() == "ư") {
            val isUUpper = state.nucleus[0].isUpperCase()
            val isOUpper = c.isUpperCase()
            // If preceded by u+w -> ư then uwo -> ươ (complete pair)
            // If preceded by standalone w (W_SOLO) -> wo -> uơ
            val isFromU = state.lastToggle?.targetType == TargetType.W_NUCLEUS
            val uStr = if (isFromU) (if (isUUpper) "Ư" else "ư") else (if (isUUpper) "U" else "u")
            val oStr = if (isOUpper) "Ơ" else "ơ"
            state.nucleus = uStr + oStr
            state.lastToggle = null
            return true
        }

        // If nucleus has 'ư' and user types 'a' -> "ưa"
        if (lower == 'a' && state.nucleus.lowercase() == "ư") {
            val isUUpper = state.nucleus[0].isUpperCase()
            val isAUpper = c.isUpperCase()
            val uStr = if (isUUpper) "Ư" else "ư"
            val aStr = if (isAUpper) "A" else "a"
            state.nucleus = uStr + aStr
            state.lastToggle = null
            return true
        }

        // If nucleus is "uơ" and user types offglide ('i', 'u') -> auto promote to "ươi", "ươu"
        if (state.nucleus.lowercase() == "uơ" && (lower == 'i' || lower == 'u')) {
            val isUUpper = state.nucleus[0].isUpperCase()
            val isOUpper = if (state.nucleus.length > 1) state.nucleus[1].isUpperCase() else false
            val uStr = if (isUUpper) "Ư" else "ư"
            val oStr = if (isOUpper) "Ơ" else "ơ"
            state.nucleus = uStr + oStr + c
            state.lastToggle = null
            return true
        }

        // If no coda yet: expand nucleus P
        if (state.coda.isEmpty()) {
            val candidate = state.nucleus + c
            if (TonePositionMap.isValidNucleusPrefix(candidate)) {
                state.nucleus = candidate
                state.lastToggle = null
                return true
            }
            // Does not match valid nucleus prefix -> append to rawSuffix (preserve without revert)
            state.lastToggle = null
            state.rawSuffix += c
            return true
        }

        // If coda already exists:
        // V-C-V boundary: If syllable has coda and previously received tone key (s, f, r, x, j),
        // a new vowel indicates that tone key was an onset consonant of next syllable (e.g. deep + s + e + e -> deepsee).
        if (state.coda.isNotEmpty()) {
            if (state.tone != Tone.NONE) {
                val toneChar = when (state.tone) {
                    Tone.ACUTE -> 's'
                    Tone.GRAVE -> 'f'
                    Tone.HOOK -> 'r'
                    Tone.TILDE -> 'x'
                    Tone.DOT -> 'j'
                    else -> null
                }
                state.tone = Tone.NONE
                if (state.nucleus.contains('ê') || state.nucleus.contains('Ê')) {
                    state.nucleus = state.nucleus.replace("ê", "ee").replace("Ê", "Ee")
                } else if (state.nucleus.contains('ô') || state.nucleus.contains('Ô')) {
                    state.nucleus = state.nucleus.replace("ô", "oo").replace("Ô", "Oo")
                } else if (state.nucleus.contains('â') || state.nucleus.contains('Â')) {
                    state.nucleus = state.nucleus.replace("â", "aa").replace("Â", "Aa")
                }
                state.lastToggle = null
                if (toneChar != null) {
                    state.rawSuffix = toneChar.toString() + c
                } else {
                    state.rawSuffix += c
                }
                return true
            }
            state.lastToggle = null
            state.rawSuffix += c
            return true
        }

        state.lastToggle = null
        state.rawSuffix += c
        return true
    }

    private fun handleConsonantChar(state: SyllableState, c: Char): Boolean {
        // 1. No vowel nucleus yet: append to onset
        if (state.nucleus.isEmpty()) {
            val candidate = state.onset + c
            if (VietnameseGrammar.isValidOnset(candidate) || state.onset.isEmpty()) {
                state.onset = candidate
                state.lastToggle = null
                return true
            }
            state.lastToggle = null
            state.rawSuffix += c
            return true
        }

        // 2. Vowel nucleus already present:
        // uơ promoted to ươ when expanding final consonant
        var effectiveNucleus = state.nucleus
        if (effectiveNucleus.lowercase() == "uơ") {
            val isUUpper = effectiveNucleus[0].isUpperCase()
            val isOUpper = if (effectiveNucleus.length > 1) effectiveNucleus[1].isUpperCase() else false
            val uStr = if (isUUpper) "Ư" else "ư"
            val oStr = if (isOUpper) "Ơ" else "ơ"
            effectiveNucleus = uStr + oStr
        }

        val candidateCoda = state.coda + c
        if ((VietnameseGrammar.isCodaPrefix(candidateCoda) || VietnameseGrammar.isValidCoda(candidateCoda)) &&
            TonePositionMap.isValidNucleusPrefix(effectiveNucleus + candidateCoda)) {
            state.nucleus = effectiveNucleus
            state.coda = candidateCoda
            state.lastToggle = null
            return true
        }

        // Invalid consonant -> append to rawSuffix (preserve transformations)
        state.lastToggle = null
        state.rawSuffix += c
        return true
    }

    private fun isVowelChar(c: Char): Boolean =
        c.lowercaseChar() in listOf('a', 'ă', 'â', 'e', 'ê', 'i', 'o', 'ô', 'ơ', 'u', 'ư', 'y')

    private fun isConsonantChar(c: Char): Boolean =
        c.lowercaseChar() in listOf(
            'b', 'c', 'd', 'đ', 'f', 'g', 'h', 'j', 'k', 'l', 'm', 'n',
            'p', 'q', 'r', 's', 't', 'v', 'w', 'x', 'z'
        )

    private fun isToneKey(c: Char): Boolean =
        c.lowercaseChar() in listOf('s', 'f', 'r', 'x', 'j', 'z')

    private fun isVowelModifierKey(c: Char): Boolean =
        c.lowercaseChar() in listOf('e', 'o', 'a', 'w')

    private fun isSeparator(c: Char): Boolean =
        c in listOf('.', ',', ';', ':', '!', '?', '-', '_', '/', '\\', '(', ')', '[', ']', '{', '}', '"', '\'', '\n', '\t')
}
