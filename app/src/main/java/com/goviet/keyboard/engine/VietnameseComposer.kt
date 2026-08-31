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
        var lastUntoggledToneKey: Char? = null,
        var rawSuffix: String = ""      // Trailing invalid characters, preserved without revert
    ) {
        fun copy(): SyllableState = SyllableState(
            onset = onset,
            nucleus = nucleus,
            coda = coda,
            tone = tone,
            lastToggle = lastToggle,
            lastUntoggledToneKey = lastUntoggledToneKey,
            rawSuffix = rawSuffix
        )

        fun setFrom(other: SyllableState) {
            onset = other.onset
            nucleus = other.nucleus
            coda = other.coda
            tone = other.tone
            lastToggle = other.lastToggle
            lastUntoggledToneKey = other.lastUntoggledToneKey
            rawSuffix = other.rawSuffix
        }

        fun reset() {
            onset = ""
            nucleus = ""
            coda = ""
            tone = Tone.NONE
            lastToggle = null
            lastUntoggledToneKey = null
            rawSuffix = ""
        }

        fun isEmpty(): Boolean = onset.isEmpty() && nucleus.isEmpty() && coda.isEmpty() && rawSuffix.isEmpty()

        fun hasTransformedLetter(): Boolean {
            for (i in 0 until onset.length) {
                if (onset[i].lowercaseChar() == 'đ') return true
            }
            for (i in 0 until nucleus.length) {
                val c = nucleus[i].lowercaseChar()
                if (c == 'â' || c == 'ă' || c == 'ê' || c == 'ô' || c == 'ơ' || c == 'ư') return true
            }
            return false
        }

        fun toDisplayString(oldTonePlacement: Boolean = false): String {
            if (isEmpty()) return ""
            val totalLen = onset.length + nucleus.length + coda.length + rawSuffix.length
            if (totalLen == 0) return ""

            if (tone == Tone.NONE || nucleus.isEmpty()) {
                val buf = CharArray(totalLen)
                var offset = 0
                for (i in 0 until onset.length) buf[offset++] = onset[i]
                for (i in 0 until nucleus.length) buf[offset++] = nucleus[i]
                for (i in 0 until coda.length) buf[offset++] = coda[i]
                for (i in 0 until rawSuffix.length) buf[offset++] = rawSuffix[i]
                return String(buf, 0, offset)
            }

            val rime = nucleus + coda
            val pos = VietnameseFiniteStateTable.findTonePosition(onset, rime, oldTonePlacement)
            val toneIdx = if (pos != null && pos in 0 until nucleus.length) pos else 0

            val buf = CharArray(totalLen)
            var offset = 0
            for (i in 0 until onset.length) {
                buf[offset++] = onset[i]
            }
            for (i in 0 until nucleus.length) {
                if (i == toneIdx) {
                    buf[offset++] = VietnameseUnicode.applyTone(nucleus[i], tone)
                } else {
                    buf[offset++] = nucleus[i]
                }
            }
            for (i in 0 until coda.length) {
                buf[offset++] = coda[i]
            }
            for (i in 0 until rawSuffix.length) {
                buf[offset++] = rawSuffix[i]
            }
            return String(buf, 0, offset)
        }
    }

    class ComposerSnapshot(
        val state: SyllableState = SyllableState(),
        var displayText: String = "",
        var ownership: CompositionOwnership = CompositionOwnership.LIVE_VIETNAMESE
    ) {
        fun set(s: SyllableState, text: String, own: CompositionOwnership = CompositionOwnership.LIVE_VIETNAMESE) {
            state.setFrom(s)
            displayText = text
            ownership = own
        }

        fun copy(): ComposerSnapshot = ComposerSnapshot(
            state = state.copy(),
            displayText = displayText,
            ownership = ownership
        )
    }

    sealed interface InternalCompositionEvent {
        data class Commit(val text: String, val separator: Char) : InternalCompositionEvent
        data class Update(val text: String, val consumed: Boolean) : InternalCompositionEvent
        data object PassThrough : InternalCompositionEvent
    }

    var ownership: CompositionOwnership = CompositionOwnership.LIVE_VIETNAMESE
    private var currentSyllable = SyllableState()
    private val snapshotPool = Array(32) { ComposerSnapshot() }
    private var undoCount = 0

    fun reset() {
        currentSyllable.reset()
        undoCount = 0
        ownership = CompositionOwnership.LIVE_VIETNAMESE
    }

    fun toDisplayString(): String = currentSyllable.toDisplayString(options.oldTonePlacement)

    fun getCurrentSyllable(): SyllableState = currentSyllable.copy()

    fun getTopSnapshot(): ComposerSnapshot? = if (undoCount > 0) snapshotPool[undoCount - 1] else null

    fun popSnapshot(): ComposerSnapshot? {
        if (undoCount > 1) {
            undoCount--
            val prev = snapshotPool[undoCount - 1]
            currentSyllable.setFrom(prev.state)
            ownership = prev.ownership
            return prev
        } else if (undoCount == 1) {
            reset()
            return null
        }
        return null
    }

    fun restoreFromSnapshot(snap: ComposerSnapshot) {
        currentSyllable.setFrom(snap.state)
        ownership = snap.ownership
    }

    fun loadAdoptedSyllable(state: SyllableState, canonicalRaw: String, snaps: List<ComposerSnapshot> = emptyList()) {
        reset()
        currentSyllable.setFrom(state)
        ownership = CompositionOwnership.ADOPTED_VIETNAMESE
        undoCount = 0
        if (snaps.isNotEmpty()) {
            val limit = minOf(snaps.size, snapshotPool.size)
            for (i in 0 until limit) {
                 val s = snaps[i]
                 snapshotPool[i].set(s.state, s.displayText, s.ownership)
            }
            undoCount = limit
        } else {
            val display = currentSyllable.toDisplayString(options.oldTonePlacement)
            snapshotPool[0].set(currentSyllable, display, CompositionOwnership.ADOPTED_VIETNAMESE)
            undoCount = 1
        }
    }

    fun loadLiteral(text: String) {
        reset()
        currentSyllable.rawSuffix = text
        ownership = CompositionOwnership.EDITED_LITERAL
        undoCount = 0
        if (snapshotPool.isNotEmpty()) {
            snapshotPool[0].set(currentSyllable, text, CompositionOwnership.EDITED_LITERAL)
            undoCount = 1
        }
    }

    /**
     * Unified Core Step Result representing the atomic outcome of feeding a character into a syllable state.
     */
    private sealed interface CoreStepResult {
        data class Boundary(val committedText: String, val separator: Char) : CoreStepResult
        data class Mutation(val displayText: String, val consumed: Boolean) : CoreStepResult
    }

    /**
     * Unified Core Step Engine: Single Source of Truth for:
     * 1. Boundary detection and syllable committing
     * 2. EDITED_LITERAL preservation
     * 3. applyKey transformation for Telex/VNI keystrokes
     */
    private fun feedChar(
        state: SyllableState,
        c: Char,
        ownership: CompositionOwnership = CompositionOwnership.LIVE_VIETNAMESE,
        isStaticReDerive: Boolean = false
    ): CoreStepResult {
        if (BoundaryClassifier.isBoundaryChar(c, isTelexMode = true)) {
            val committed = state.toDisplayString(options.oldTonePlacement)
            state.reset()
            return CoreStepResult.Boundary(committed, c)
        }

        if (ownership == CompositionOwnership.EDITED_LITERAL) {
            state.rawSuffix += c
            val displayText = state.toDisplayString(options.oldTonePlacement)
            return CoreStepResult.Mutation(displayText, consumed = true)
        }

        val success = applyKey(state, c, isStaticReDerive = isStaticReDerive)
        val displayText = state.toDisplayString(options.oldTonePlacement)
        return CoreStepResult.Mutation(displayText, consumed = success)
    }

    /**
     * Synchronizes and processes the raw string through the authoritative state machine.
     * Updates currentSyllable and snapshotPool synchronously.
     */
    fun syncStateFromRaw(raw: String, own: CompositionOwnership = CompositionOwnership.LIVE_VIETNAMESE): Pair<String, ComposerSnapshot> {
        if (own == CompositionOwnership.EDITED_LITERAL) {
            loadLiteral(raw)
            return Pair(raw, snapshotPool[0].copy())
        }
        reset()
        ownership = own
        val sb = StringBuilder()
        for (c in raw) {
            when (val step = feedChar(currentSyllable, c, ownership, isStaticReDerive = false)) {
                is CoreStepResult.Boundary -> {
                    sb.append(step.committedText).append(step.separator)
                }
                is CoreStepResult.Mutation -> {
                    if (undoCount < snapshotPool.size) {
                        snapshotPool[undoCount].set(currentSyllable, step.displayText, ownership)
                        undoCount++
                    }
                }
            }
        }
        val currentDisplay = currentSyllable.toDisplayString(options.oldTonePlacement)
        val finalDisplay = if (sb.isNotEmpty()) sb.toString() + currentDisplay else currentDisplay
        val topSnap = if (undoCount > 0) snapshotPool[undoCount - 1].copy() else ComposerSnapshot(currentSyllable, finalDisplay, ownership)
        return Pair(finalDisplay, topSnap)
    }

    /**
     * Internal unified state processing for interactive key presses.
     */
    private fun processInternal(c: Char): InternalCompositionEvent {
        return when (val step = feedChar(currentSyllable, c, ownership, isStaticReDerive = false)) {
            is CoreStepResult.Boundary -> {
                reset()
                if (step.committedText.isNotEmpty()) {
                    InternalCompositionEvent.Commit(step.committedText, step.separator)
                } else {
                    InternalCompositionEvent.PassThrough
                }
            }
            is CoreStepResult.Mutation -> {
                if (undoCount < snapshotPool.size) {
                    snapshotPool[undoCount].set(currentSyllable, step.displayText, ownership)
                    undoCount++
                }
                InternalCompositionEvent.Update(text = step.displayText, consumed = step.consumed)
            }
        }
    }

    /**
     * Canonical single key processor returning modern Zero-Allocation CompositionResult.
     */
    fun processKey(c: Char): CompositionResult {
        return when (val event = processInternal(c)) {
            is InternalCompositionEvent.Commit -> CompositionResult.CommitAndStartNew(event.text, event.separator)
            is InternalCompositionEvent.PassThrough -> CompositionResult.PassThrough
            is InternalCompositionEvent.Update -> CompositionResult.Update(text = event.text)
        }
    }

    /**
     * Process backspace using unified VietnameseEditReducer.
     */
    fun backspace(): String {
        val currentDisplay = currentSyllable.toDisplayString(options.oldTonePlacement)
        if (currentDisplay.isEmpty()) {
            reset()
            return ""
        }

        val res = VietnameseEditReducer.reduceBackspace(
            currentDisplay = currentDisplay,
            cursorInDisplay = currentDisplay.length,
            currentOwnership = ownership,
            options = options
        )

        if (res.display.isEmpty()) {
            reset()
            return ""
        }

        currentSyllable.setFrom(res.syllableState)
        ownership = res.ownership
        undoCount = 0
        if (res.snapshots.isNotEmpty()) {
            val limit = minOf(res.snapshots.size, snapshotPool.size)
            for (i in 0 until limit) {
                val s = res.snapshots[i]
                snapshotPool[i].set(s.state, s.displayText, s.ownership)
            }
            undoCount = limit
        } else {
            snapshotPool[0].set(currentSyllable, res.display, ownership)
            undoCount = 1
        }
        return res.display
    }

    /**
     * Deconstructs any Vietnamese word into its canonical keystrokes and authentic
     * step-by-step composing snapshots. Used for instant cursor adoption and stateless undo.
     */
    fun generateDeconstructedSnapshots(analysis: VietnameseLexicalParser.AnalysisResult): Pair<String, List<ComposerSnapshot>> {
        return VietnameseSnapshotBuilder.generate(analysis, options)
    }

    fun generateDeconstructedSnapshots(word: String): Pair<String, List<ComposerSnapshot>> {
        return VietnameseSnapshotBuilder.generate(word, options)
    }

    /**
     * Process raw character sequence independently (Stateless).
     * Must NOT mutate interactive session state (currentSyllable, snapshotPool).
     */
    fun processString(raw: String): String {
        if (raw.isEmpty()) return ""

        val sb = StringBuilder()
        val tempSyllable = SyllableState()

        for (c in raw) {
            when (val step = feedChar(tempSyllable, c, CompositionOwnership.LIVE_VIETNAMESE, isStaticReDerive = false)) {
                is CoreStepResult.Boundary -> {
                    sb.append(step.committedText).append(step.separator)
                }
                is CoreStepResult.Mutation -> {
                    // Stateless mutation - no snapshot overhead needed
                }
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

        // Completed-Word Detector: ensure confident Vietnamese syllable structure
        if (!EditedVietnameseRecognizer.canRecompose(word, options)) {
            return word
        }

        val tempSyllable = SyllableState()
        for (c in word) {
            feedChar(tempSyllable, c, CompositionOwnership.LIVE_VIETNAMESE, isStaticReDerive = true)
        }
        val result = tempSyllable.toDisplayString(options.oldTonePlacement)
        return VietnameseCharUtils.applyCasingFromRaw(result, word)
    }

    companion object {
        fun compile(raw: String, options: EngineOptions = EngineOptions()): String {
            return VietnameseComposer(options).processString(raw)
        }
    }

    /**
     * Apply a single character c to the syllable state.
     */
    private fun applyKey(state: SyllableState, c: Char, isStaticReDerive: Boolean): Boolean {
        val lower = c.lowercaseChar()
        val isUpper = c.isUpperCase()

        // If rawSuffix already exists:
        if (state.rawSuffix.isNotEmpty()) {
            // Telex modifier key recovery: only when last raw suffix is 'w' on a consonant coda like 'nw' in 'nhanw' + 'n'
            // specifically if user wants 'a' -> 'nhân'
            if (lower == 'a' && state.rawSuffix == "n" && state.coda == "n" && state.nucleus.contains('ă')) {
                val handled = handleVowelModifierKey(state, lower, isUpper, isStaticReDerive)
                if (handled) {
                    state.rawSuffix = ""
                    return true
                }
            }
            state.lastToggle = null
            state.rawSuffix += c
            return true
        }

        // Telex brackets shortcut: [ -> ư, ] -> ơ
        if (lower == '[') {
            state.lastToggle = null
            return applyBracketKey(state, 'ư', isUpper)
        } else if (lower == ']') {
            state.lastToggle = null
            return applyBracketKey(state, 'ơ', isUpper)
        }

        // STEP 0: Character 'd'
        if (lower == 'd') {
            val handled = handleKeyD(state, c)
            if (handled) return true
        }

        // STEP 1: Tone marks (s, f, r, x, j, z)
        if (isToneKey(lower)) {
            val handled = handleToneKey(state, lower)
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
        } else if (state.coda.isEmpty() && VietnameseFiniteStateTable.isValidPrefix(state.nucleus + v)) {
            state.nucleus += v
        } else {
            state.rawSuffix += v
        }
        return true
    }

    private fun handleKeyD(state: SyllableState, c: Char): Boolean {
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

    private fun handleToneKey(state: SyllableState, key: Char): Boolean {
        // Requires vowel nucleus P
        if (state.nucleus.isEmpty()) return false

        // Check valid coda
        if (state.coda.isNotEmpty() && !VietnameseFiniteStateTable.isValidCoda(state.coda)) {
            return false
        }

        val targetTone = Tone.fromKey(key) ?: return false

        // Validate tone legality with current rime (e.g., closed stops -p, -t, -c, -ch can only have acute or dot)
        val currentRime = state.nucleus + state.coda
        if (!VietnameseFiniteStateTable.isValidToneForRime(currentRime, targetTone)) {
            return false
        }

        // 'z' is the explicit tone removal key: removes tone and does not append 'z'
        if (key == 'z') {
            if (state.tone != Tone.NONE) {
                state.tone = Tone.NONE
                state.lastToggle = null
                return true
            }
            return false
        }

        // Toggle tone (Standard UniKey Telex behavior):
        // If the same tone already exists -> remove tone and append literal key to rawSuffix (escape to raw)
        // e.g. á + s -> as, ắn + s -> ăns, tối + s -> tôis, đừng + f -> đưngf, toán + s -> toans
        if (state.tone == targetTone) {
            state.tone = Tone.NONE
            state.rawSuffix += key
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
            'o' -> return toggleNucleusChar(state, charArrayOf('o', 'ơ'), 'ô', TargetType.O_NUCLEUS, 'o')
            'a' -> return toggleNucleusChar(state, charArrayOf('a', 'ă'), 'â', TargetType.A_NUCLEUS, 'a')
            'w' -> return handleKeyW(state, isUpper)
        }

        return false
    }

    private fun replaceCharAt(str: String, idx: Int, replacement: Char): String {
        val arr = str.toCharArray()
        arr[idx] = replacement
        return String(arr)
    }

    private fun toggleNucleusChar(
        state: SyllableState,
        fromChars: CharArray,
        toChar: Char,
        targetType: TargetType,
        key: Char
    ): Boolean {
        val p = state.nucleus
        if (p.isEmpty()) return false

        var idx = -1
        for (i in 0 until p.length) {
            val cLower = p[i].lowercaseChar()
            for (from in fromChars) {
                if (cLower == from) {
                    idx = i
                    break
                }
            }
            if (idx != -1) break
        }
        if (idx == -1) return false

        val isCharUpper = p[idx].isUpperCase()
        val replacement = if (isCharUpper) toChar.uppercaseChar() else toChar
        val newNucleus = replaceCharAt(p, idx, replacement)

        // Safety check (Step 4)
        val newRime = newNucleus + state.coda
        if (!VietnameseFiniteStateTable.isValidPrefix(newRime)) {
            return false
        }

        val hadCharsAfter = state.coda.isNotEmpty() || (idx < p.length - 1)
        state.nucleus = newNucleus
        state.lastToggle = LastToggle(key = key, targetType = targetType, hadCharsAfter = hadCharsAfter)
        return true
    }

    private fun toggleNucleusChar(
        state: SyllableState,
        fromChar: Char,
        toChar: Char,
        targetType: TargetType,
        key: Char
    ): Boolean = toggleNucleusChar(state, charArrayOf(fromChar), toChar, targetType, key)

    /**
     * Builds the Telex "uơ"/"ươ" cluster from its two source characters, preserving
     * the casing of each source. When [hornU] is true the first character is emitted
     * as "ư"/"Ư" (rhyme "ươ"); otherwise it stays "u"/"U" (open-rhyme "uơ").
     * [tail] is an optional already-cased character appended unchanged (e.g. offglide).
     */
    private fun buildUoPair(uChar: Char, oChar: Char, hornU: Boolean = true, tail: Char? = null): String {
        val uStr = if (hornU) {
            if (uChar.isUpperCase()) "Ư" else "ư"
        } else {
            if (uChar.isUpperCase()) "U" else "u"
        }
        val oStr = if (oChar.isUpperCase()) "Ơ" else "ơ"
        return if (tail != null) uStr + oStr + tail else uStr + oStr
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
            // Standard Telex Phonological Rule:
            // uow is uơ (open rime, e.g. uow -> uơ, huow -> huơ, thuow -> thuơ, quow -> quơ, tuow -> tuơ).
            // When combined with coda/offglide (uowng, huowng, uoi, uou...) -> ươ
            val hasCodaOrOffglide = state.coda.isNotEmpty() || pLower == "uoi" || pLower == "uou"

            val transformed = buildUoPair(state.nucleus[0], state.nucleus[1], hornU = hasCodaOrOffglide)

            val newNucleus = state.nucleus.replaceRange(0, 2, transformed)
            val newRime = newNucleus + state.coda
            if (VietnameseFiniteStateTable.isValidPrefix(newRime)) {
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
            if (VietnameseFiniteStateTable.isValidPrefix(newRime)) {
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
            if (VietnameseFiniteStateTable.isValidPrefix(newRime)) {
                state.nucleus = newNucleus
                state.lastToggle = LastToggle(key = 'w', targetType = TargetType.W_NUCLEUS, hadCharsAfter = state.coda.isNotEmpty())
                return true
            }
        }

        // 5. Target letter 'o' / 'ô' -> 'ơ' (e.g. oi -> ơi, moiw -> mơi)
        if ((pLower.contains('o') || pLower.contains('ô')) && !pLower.contains('ơ')) {
            return toggleNucleusChar(state, charArrayOf('o', 'ô'), 'ơ', TargetType.W_NUCLEUS, 'w')
        }

        // 6. Target letter 'u' -> 'ư' (e.g. u -> ư, ui -> ưi, dungw -> dưng)
        // If onset is 'q', do not convert 'u' to 'ư' since 'qư' is invalid in Vietnamese
        if (pLower.contains('u') && !pLower.contains('ư') && state.onset.lowercase() != "q") {
            return toggleNucleusChar(state, 'u', 'ư', TargetType.W_NUCLEUS, 'w')
        }

        // 7. Target letter 'a' / 'â' -> 'ă' (e.g. a -> ă, banw -> băn, langw -> lăng, bânw -> băn)
        if ((pLower.contains('a') || pLower.contains('â')) && !pLower.contains('ă')) {
            return toggleNucleusChar(state, charArrayOf('a', 'â'), 'ă', TargetType.W_NUCLEUS, 'w')
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
            val isGUpper = state.onset[0].isUpperCase()
            state.onset = if (isGUpper) "Gi" else "gi"
            state.nucleus = c.toString()
            state.lastToggle = null
            return true
        }

        // Preprocess qu:
        // If onset is "q" and nucleus is "u", and user enters another vowel:
        // "qu" becomes onset, and new vowel becomes nucleus (e.g. queo -> onset "qu", nucleus "eo", que -> onset "qu", nucleus "e")
        if (state.onset.lowercase() == "q" && state.nucleus.lowercase() == "u" && state.coda.isEmpty()) {
            val isQUpper = state.onset[0].isUpperCase()
            val isUUpper = state.nucleus[0].isUpperCase()
            state.onset = if (isQUpper && isUUpper) "QU" else if (isQUpper) "Qu" else "qu"
            state.nucleus = c.toString()
            state.lastToggle = null
            return true
        }

        // If nucleus has 'ư' and user types 'o' -> "ươ" (e.g. wo -> ươ, mwo -> mươ, uwo -> ươ)
        if (lower == 'o' && state.nucleus.lowercase() == "ư") {
            state.nucleus = buildUoPair(state.nucleus[0], c)
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
            state.nucleus = buildUoPair(state.nucleus[0], state.nucleus[1], tail = c)
            state.lastToggle = null
            return true
        }

        // If no coda yet: expand nucleus P
        if (state.coda.isEmpty()) {
            val candidate = state.nucleus + c
            if (VietnameseFiniteStateTable.isValidPrefix(candidate)) {
                state.nucleus = candidate
                state.lastToggle = null
                return true
            }
            // Does not match valid nucleus prefix -> append to rawSuffix (preserve without revert)
            state.lastToggle = null
            state.rawSuffix += c
            return true
        }

        // If coda already exists: new vowel cannot attach to coda -> append to rawSuffix without removing existing tone or letters
        state.lastToggle = null
        state.rawSuffix += c
        return true
    }

    private fun handleConsonantChar(state: SyllableState, c: Char): Boolean {
        // 1. No vowel nucleus yet: append to onset
        if (state.nucleus.isEmpty()) {
            val candidate = state.onset + c
            if (VietnameseFiniteStateTable.isValidOnset(candidate) || state.onset.isEmpty()) {
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
            effectiveNucleus = buildUoPair(effectiveNucleus[0], effectiveNucleus[1])
        }

        val candidateCoda = state.coda + c
        val candidateRime = effectiveNucleus + candidateCoda
        if (VietnameseFiniteStateTable.isValidCoda(candidateCoda) &&
            VietnameseFiniteStateTable.isValidPrefix(candidateRime) &&
            VietnameseFiniteStateTable.isValidToneForRime(candidateRime, state.tone)) {
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

    private fun isVowelChar(c: Char): Boolean = VietnameseLexicon.isBaseVowel(c)

    private fun isConsonantChar(c: Char): Boolean = VietnameseLexicon.isConsonant(c)

    private fun isToneKey(c: Char): Boolean = c.lowercaseChar() in setOf('s', 'f', 'r', 'x', 'j', 'z')

    private fun isVowelModifierKey(c: Char): Boolean = c.lowercaseChar() in setOf('e', 'o', 'a', 'w')
}
