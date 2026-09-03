package com.goviet.keyboard.engine

import android.content.Context
import com.goviet.core.AppPreferences
import com.goviet.core.EngineConfig

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

    var vietnameseModeEnabled: Boolean = true
    var autoCapitalize: Boolean = false

    var macroEnabled: Boolean
        get() = options.macroEnabled
        set(v) { options.macroEnabled = v }

    var alwaysMacro: Boolean
        get() = options.alwaysMacro
        set(v) { options.alwaysMacro = v }

    var directW: Boolean
        get() = options.directW
        set(v) { options.directW = v }

    var oldTonePlacement: Boolean
        get() = options.oldTonePlacement
        set(v) { options.oldTonePlacement = v }

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

        fun toDisplayString(oldTonePlacement: Boolean = false): String {
            if (isEmpty()) return ""
            val totalLen = onset.length + nucleus.length + coda.length + rawSuffix.length
            if (totalLen == 0) return ""

            if (tone == Tone.NONE || nucleus.isEmpty()) {
                val buf = VietnameseComposer.ensureBuffer(totalLen)
                var offset = 0
                for (i in 0 until onset.length) buf[offset++] = onset[i]
                for (i in 0 until nucleus.length) buf[offset++] = nucleus[i]
                for (i in 0 until coda.length) buf[offset++] = coda[i]
                for (i in 0 until rawSuffix.length) buf[offset++] = rawSuffix[i]
                return String(buf, 0, offset)
            }

            val rime = nucleus + coda
            val placement = if (oldTonePlacement) TonePlacement.LEGACY else TonePlacement.MODERN
            val toneIdx = VietnameseSpellingGuide.determineTonePosition(rime, onset, placement)

            val buf = VietnameseComposer.ensureBuffer(totalLen)
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

        /**
         * Write display text directly to an OwnedBuffer. Zero allocation.
         * Used in feedChar hot path to avoid String creation.
         */
        fun toDisplayBuffer(out: OwnedBuffer, oldTonePlacement: Boolean = false) {
            out.clear()
            if (isEmpty()) return
            val totalLen = onset.length + nucleus.length + coda.length + rawSuffix.length
            if (totalLen == 0) return

            if (tone == Tone.NONE || nucleus.isEmpty()) {
                for (i in 0 until onset.length) out.append(onset[i])
                for (i in 0 until nucleus.length) out.append(nucleus[i])
                for (i in 0 until coda.length) out.append(coda[i])
                for (i in 0 until rawSuffix.length) out.append(rawSuffix[i])
                return
            }

            val rime = nucleus + coda
            val placement = if (oldTonePlacement) TonePlacement.LEGACY else TonePlacement.MODERN
            val toneIdx = VietnameseSpellingGuide.determineTonePosition(rime, onset, placement)

            for (i in 0 until onset.length) out.append(onset[i])
            for (i in 0 until nucleus.length) {
                if (i == toneIdx) out.append(VietnameseUnicode.applyTone(nucleus[i], tone))
                else out.append(nucleus[i])
            }
            for (i in 0 until coda.length) out.append(coda[i])
            for (i in 0 until rawSuffix.length) out.append(rawSuffix[i])
        }
    }

    class ComposerSnapshot(
        val state: SyllableState = SyllableState(),
        val displayBuffer: OwnedBuffer = OwnedBuffer(),
        var isVietnamese: Boolean = true
    ) {
        val displayText: String get() = displayBuffer.toStringVal()
        val displayLen: Int get() = displayBuffer.len

        fun set(s: SyllableState, buf: OwnedBuffer, len: Int, vietnamese: Boolean = true) {
            state.setFrom(s)
            displayBuffer.clear()
            if (len > 0) displayBuffer.append(buf, 0, len)
            isVietnamese = vietnamese
        }

        fun setFromDisplay(s: SyllableState, text: String, vietnamese: Boolean = true) {
            state.setFrom(s)
            displayBuffer.clear()
            displayBuffer.append(text)
            isVietnamese = vietnamese
        }

        fun setFrom(other: ComposerSnapshot) {
            state.setFrom(other.state)
            displayBuffer.clear()
            if (other.displayBuffer.isNotEmpty()) displayBuffer.append(other.displayBuffer)
            isVietnamese = other.isVietnamese
        }

        fun copy(): ComposerSnapshot {
            val snap = ComposerSnapshot()
            snap.setFrom(this)
            return snap
        }
    }

    /**
     * Zero-allocation result container for syncStateFromRaw. Reused across keystrokes.
     */
    class SyncResult {
        val displayBuffer = OwnedBuffer()
        val snapshot = ComposerSnapshot()
        val displayText: String get() = displayBuffer.toStringVal()
        val displayLen: Int get() = displayBuffer.len

        fun setFromResult(buf: OwnedBuffer, len: Int, state: SyllableState, vietnamese: Boolean = true) {
            displayBuffer.clear()
            if (len > 0) displayBuffer.append(buf, 0, len)
            snapshot.set(state, buf, len, vietnamese)
        }

        fun setFromDisplay(display: String, state: SyllableState, vietnamese: Boolean = true) {
            displayBuffer.clear()
            displayBuffer.append(display)
            snapshot.setFromDisplay(state, display, vietnamese)
        }
    }

    var isVietnamese: Boolean = true
    private var currentSyllable = SyllableState()
    private val snapshotPool = Array(32) { ComposerSnapshot() }
    private var undoCount = 0
    private val stepOutPool = StepOut()
    
    // Reusable buffers for syncStateFromRaw — eliminates per-keystroke StringBuilder allocation
    private val committedBuffer = OwnedBuffer()
    private val resultBuffer = OwnedBuffer()

    fun reset() {
        currentSyllable.reset()
        undoCount = 0
        isVietnamese = true
    }

    fun toDisplayString(): String = currentSyllable.toDisplayString(options.oldTonePlacement)

    fun getTopSnapshot(): ComposerSnapshot? = if (undoCount > 0) snapshotPool[undoCount - 1] else null

    fun loadSyllable(state: SyllableState, vietnamese: Boolean = true) {
        reset()
        currentSyllable.setFrom(state)
        isVietnamese = vietnamese
    }

    private enum class CoreStep { BOUNDARY, MUTATION }

    private class StepOut {
        val committedBuffer = OwnedBuffer()
        var separator: Char = ' '
        val displayBuffer = OwnedBuffer()
    }

    private fun feedChar(
        state: SyllableState,
        c: Char,
        vietnamese: Boolean = true,
        isStaticReDerive: Boolean = false,
        out: StepOut
    ): CoreStep {
        if (BoundaryClassifier.isBoundaryChar(c)) {
            out.committedBuffer.clear()
            state.toDisplayBuffer(out.committedBuffer, options.oldTonePlacement)
            out.separator = c
            state.reset()
            return CoreStep.BOUNDARY
        }

        if (!vietnamese) {
            state.rawSuffix += c
            out.displayBuffer.clear()
            state.toDisplayBuffer(out.displayBuffer, options.oldTonePlacement)
            return CoreStep.MUTATION
        }

        applyKey(state, c, isStaticReDerive = isStaticReDerive)
        out.displayBuffer.clear()
        state.toDisplayBuffer(out.displayBuffer, options.oldTonePlacement)
        return CoreStep.MUTATION
    }


    fun syncStateFromRaw(raw: String, vietnamese: Boolean, out: SyncResult): SyncResult {
        return syncStateFromRaw(raw as CharSequence, vietnamese, out)
    }

    fun syncStateFromRaw(raw: CharSequence, vietnamese: Boolean, out: SyncResult): SyncResult {
        val rawLen = raw.length
        if (rawLen == 0) {
            reset()
            out.setFromDisplay("", currentSyllable, true)
            return out
        }

        if (!vietnamese) {
            val rawStr = raw.toString()
            loadSyllable(VietnameseComposer.SyllableState(rawSuffix = rawStr), false)
            out.setFromDisplay(rawStr, currentSyllable, false)
            return out
        }

        reset()
        isVietnamese = true
        committedBuffer.clear()
        val stepOut = stepOutPool
        for (i in 0 until rawLen) {
            when (feedChar(currentSyllable, raw[i], true, isStaticReDerive = false, out = stepOut)) {
                CoreStep.BOUNDARY -> {
                    committedBuffer.append(stepOut.committedBuffer)
                    committedBuffer.append(stepOut.separator)
                }
                CoreStep.MUTATION -> { /* display updated via feedChar */ }
            }
        }
        val currentDisplay = currentSyllable.toDisplayString(options.oldTonePlacement)
        resultBuffer.clear()
        if (committedBuffer.isNotEmpty()) resultBuffer.append(committedBuffer)
        resultBuffer.append(currentDisplay)
        out.setFromResult(resultBuffer, resultBuffer.len, currentSyllable, isVietnamese)
        return out
    }

    private val keyResult = KeyResult()

    /**
     * Public single-key processor returning [CompositionResult].
     * Wraps the internal key processing which returns zero-allocation [KeyResult].
     */
    fun processKey(key: Char): CompositionResult {
        if (!vietnameseModeEnabled) return CompositionResult.PassThrough
        val kr = processKeyInternal(key)
        return when (kr.kind) {
            KeyResult.Kind.COMMIT -> CompositionResult.CommitAndStartNew(kr.commitText, kr.separator)
            KeyResult.Kind.UPDATE -> CompositionResult.Update(kr.updateText)
            KeyResult.Kind.PASS_THROUGH -> CompositionResult.PassThrough
        }
    }

    /** Internal single key processor returning zero-allocation [KeyResult]. */
    fun processKeyInternal(c: Char): KeyResult {
        val stepOut = stepOutPool
        val out = keyResult
        when (feedChar(currentSyllable, c, isVietnamese, isStaticReDerive = false, out = stepOut)) {
            CoreStep.BOUNDARY -> {
                val committed = stepOut.committedBuffer.toStringVal()
                val separator = stepOut.separator
                reset()
                if (committed.isNotEmpty()) {
                    out.reset(KeyResult.Kind.COMMIT)
                    out.commitText = committed
                    out.separator = separator
                } else {
                    out.reset(KeyResult.Kind.PASS_THROUGH)
                }
            }
            CoreStep.MUTATION -> {
                if (undoCount < snapshotPool.size) {
                    snapshotPool[undoCount].set(currentSyllable, stepOut.displayBuffer, stepOut.displayBuffer.len, isVietnamese)
                    undoCount++
                }
                out.reset(KeyResult.Kind.UPDATE)
                out.updateText = stepOut.displayBuffer.toStringVal()
            }
        }
        return out
    }

    fun backspace(): String {
        val currentDisplay = currentSyllable.toDisplayString(options.oldTonePlacement)
        if (currentDisplay.isEmpty()) {
            reset()
            return ""
        }

        val res = VietnameseEditReducer.reduceBackspace(
            currentDisplay = currentDisplay,
            cursorInDisplay = currentDisplay.length,
            currentOwnership = if (isVietnamese) CompositionMode.VIETNAMESE else CompositionMode.LITERAL,
            options = options
        )

        if (res.display.isEmpty()) {
            reset()
            return ""
        }

        currentSyllable.setFrom(res.syllableState)
        isVietnamese = (res.ownership == CompositionMode.VIETNAMESE)
        return res.display
    }

    fun generateDeconstructedSnapshots(analysis: VietnameseLexicalParser.AnalysisResult): Pair<String, List<ComposerSnapshot>> {
        return VietnameseSnapshotBuilder.generate(analysis, options)
    }

    fun generateDeconstructedSnapshots(word: String): Pair<String, List<ComposerSnapshot>> {
        return VietnameseSnapshotBuilder.generate(word, options)
    }

    fun process(raw: String): String = processString(raw)

    fun processString(raw: String): String {
        if (!vietnameseModeEnabled) return raw
        if (raw.isEmpty()) return ""

        val sb = StringBuilder()
        val tempSyllable = SyllableState()
        val stepOut = StepOut()

        for (c in raw) {
            when (feedChar(tempSyllable, c, true, isStaticReDerive = false, out = stepOut)) {
                CoreStep.BOUNDARY -> {
                    sb.append(stepOut.committedBuffer.toStringVal()).append(stepOut.separator)
                }
                CoreStep.MUTATION -> {
                    // Stateless mutation - no snapshot overhead needed
                }
            }
        }
        sb.append(tempSyllable.toDisplayString(options.oldTonePlacement))
        return sb.toString()
    }

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

        if (!EditedVietnameseRecognizer.canRecompose(word, options)) {
            return word
        }

        val tempSyllable = SyllableState()
        val stepOut = StepOut()
        for (c in word) {
            feedChar(tempSyllable, c, true, isStaticReDerive = true, out = stepOut)
        }
        val result = tempSyllable.toDisplayString(options.oldTonePlacement)
        return VietnameseUnicode.applyCasingFromRaw(result, word)
    }

    companion object {
        private val displayBuffer = ThreadLocal.withInitial { CharArray(32) }

        private fun ensureBuffer(size: Int): CharArray {
            val current = displayBuffer.get() ?: return CharArray(size.coerceAtLeast(64))
            return if (current.size >= size) current else {
                val grown = CharArray(size.coerceAtLeast(64))
                displayBuffer.set(grown)
                grown
            }
        }

        @JvmStatic
        fun isToneKey(c: Char): Boolean = when (c.lowercaseChar()) {
            's', 'f', 'r', 'x', 'j', 'z' -> true
            else -> false
        }

        @JvmStatic
        fun isVowelModifierKey(c: Char): Boolean = c.lowercaseChar() in VOWEL_MODIFIER_KEYS


        /** Modifier key → TargetType for fold dispatch. */
        private val MODIFIER_TARGET_TYPES = mapOf(
            'e' to TargetType.E_NUCLEUS,
            'o' to TargetType.O_NUCLEUS,
            'a' to TargetType.A_NUCLEUS
        )

        /** Nucleus auto-promotion: uơ → ươ when consonant follows. */
        private val NUCLEUS_AUTOPROMOTIONS = mapOf("uơ" to true)

        private val VOWEL_MODIFIER_KEYS = setOf('e', 'o', 'a', 'w')
    }

    private fun applyKey(state: SyllableState, c: Char, isStaticReDerive: Boolean): Boolean {
        val lower = c.lowercaseChar()
        val isUpper = c.isUpperCase()

        if (state.rawSuffix.isNotEmpty()) {
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

        if (lower == 'd') {
            val handled = handleKeyD(state, c)
            if (handled) return true
        }

        if (isToneKey(lower)) {
            val handled = handleToneKey(state, lower)
            if (handled) return true
        }

        if (isVowelModifierKey(lower)) {
            val handled = handleVowelModifierKey(state, lower, isUpper, isStaticReDerive)
            if (handled) return true
        }

        if (VietnameseLexicon.isBaseVowel(lower)) {
            return handleVowelChar(state, c)
        }

        if (VietnameseLexicon.isConsonant(lower)) {
            return handleConsonantChar(state, c)
        }

        state.lastToggle = null
        state.rawSuffix += c
        return true
    }

    private fun handleKeyD(state: SyllableState, c: Char): Boolean {
        val isUpper = c.isUpperCase()

        if (state.lastToggle?.key == 'd' && (state.onset.lowercase() == "đ")) {
            val dChar = if (state.onset[0].isUpperCase()) "D" else "d"
            state.onset = dChar
            val extraChar = if (isUpper) "D" else "d"
            if (state.nucleus.isEmpty() && state.coda.isEmpty() && state.rawSuffix.isEmpty()) {
                state.onset = dChar + extraChar
            } else {
                state.rawSuffix += extraChar
            }
            state.lastToggle = null
            return true
        }

        val onsetLower = state.onset.lowercase()
        if (onsetLower == "d") {
            val dChar = if (state.onset[0].isUpperCase()) "Đ" else "đ"
            state.onset = dChar
            val hadCharsAfter = state.nucleus.isNotEmpty() || state.coda.isNotEmpty()
            state.lastToggle = LastToggle(key = 'd', targetType = TargetType.D_ONSET, hadCharsAfter = hadCharsAfter)
            return true
        }

        if (state.onset.isEmpty() && state.nucleus.isEmpty() && state.coda.isEmpty()) {
            state.onset = c.toString()
            state.lastToggle = null
            return true
        }

        state.lastToggle = null
        state.rawSuffix += c
        return true
    }

    private fun handleToneKey(state: SyllableState, key: Char): Boolean {
        if (state.nucleus.isEmpty()) return false

        if (state.coda.isNotEmpty() && !VietnameseFiniteStateTable.isValidCoda(state.coda)) {
            return false
        }

        val targetTone = Tone.fromKey(key) ?: return false

        val nucleusLower = state.nucleus.lowercase()
        if (nucleusLower == "aa" || nucleusLower == "ee") {
            return false
        }

        val currentRime = state.nucleus + state.coda
        if (!VietnameseFiniteStateTable.isValidPrefix(currentRime)) {
            return false
        }

        if (!VietnameseFiniteStateTable.isValidToneForRime(currentRime, targetTone)) {
            return false
        }

        if (key == 'z') {
            if (state.tone != Tone.NONE) {
                state.tone = Tone.NONE
                state.lastToggle = null
                return true
            }
            return false
        }

        if (state.tone == targetTone) {
            state.tone = Tone.NONE
            state.rawSuffix += key
            state.lastToggle = null
            return true
        } else {
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
        if (state.lastToggle?.key == key) {
            val toggle = state.lastToggle!!
            val untoggled = untoggleVowelModifier(state, toggle, key, isUpper)
            if (untoggled) {
                state.lastToggle = null
                return true
            }
        }

        if (isStaticReDerive && (key == 'a' || key == 'e' || key == 'u') && key != 'o') {
            return false
        }

        val targetType = MODIFIER_TARGET_TYPES[key]
        if (targetType != null) {
            val res = VietnameseSpellingGuide.FoldResult()
            if (VietnameseSpellingGuide.foldSingle(
                    state.nucleus,
                    VietnameseSpellingGuide.foldRulesFor(key),
                    state.coda,
                    state.onset,
                    res
                )
            ) {
                state.nucleus = res.nucleus
                state.lastToggle = LastToggle(key, targetType, res.hadCharsAfter)
                return true
            }
            return false
        }

        if (key == 'w') return handleKeyW(state, isUpper)
        return false
    }

    private fun handleKeyW(state: SyllableState, isUpper: Boolean): Boolean {
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

        val result = VietnameseSpellingGuide.applyW(state.nucleus, state.coda, state.onset)
        if (result != null) {
            state.nucleus = result.first
            result.second?.let { state.lastToggle = it }
            return true
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
            val extraChar = if (isUpper) 'W' else 'w'
            state.nucleus = ""
            state.rawSuffix = extraChar.toString()
            return true
        }

        val res = VietnameseSpellingGuide.UnfoldResult()
        if (!VietnameseSpellingGuide.unfold(state.nucleus, toggle.targetType, isUpper, res)) {
            return false
        }
        state.nucleus = res.nucleus

        if (state.coda.isEmpty() && state.rawSuffix.isEmpty() && res.foldedIndex == res.nucleus.length - 1) {
            state.nucleus += res.tail
            return true
        }
        state.rawSuffix += res.tail
        return true
    }

    private fun handleVowelChar(state: SyllableState, c: Char): Boolean {
        val lower = c.lowercaseChar()

        // 1. Onset promotion: gi+V → onset "gi", V becomes nucleus; qu+V → onset "qu", V becomes nucleus
        val promotedOnset = VietnameseSpellingGuide.lookupOnsetPromotion(state.onset, state.nucleus)
        if (promotedOnset != null && state.coda.isEmpty()) {
            val isOrigUpper = state.onset[0].isUpperCase()
            state.onset = if (isOrigUpper) promotedOnset.replaceFirstChar { it.uppercase() } else promotedOnset
            state.nucleus = c.toString()
            state.lastToggle = null
            return true
        }

        // 2. Vowel combination: ư+o→ươ, ư+a→ưa, uơ+i→ươi, uơ+u→ươu
        val combo = VietnameseSpellingGuide.lookupVowelCombination(state.nucleus, c)
        if (combo != null) {
            state.nucleus = combo
            state.lastToggle = null
            return true
        }

        // 3. Normal vowel expansion into nucleus (if no coda yet)
        if (state.coda.isEmpty()) {
            val candidate = state.nucleus + c
            if (VietnameseFiniteStateTable.isValidPrefix(candidate)) {
                state.nucleus = candidate
                state.lastToggle = null
                return true
            }
            state.lastToggle = null
            state.rawSuffix += c
            return true
        }

        // 4. Coda exists → append to raw suffix
        state.lastToggle = null
        state.rawSuffix += c
        return true
    }

    private fun handleConsonantChar(state: SyllableState, c: Char): Boolean {
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

        var effectiveNucleus = state.nucleus
        if (NUCLEUS_AUTOPROMOTIONS.containsKey(effectiveNucleus.lowercase())) {
            effectiveNucleus = VietnameseSpellingGuide.buildUoPair(effectiveNucleus[0], effectiveNucleus[1], hornU = true)
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

        state.lastToggle = null
        state.rawSuffix += c
        return true
    }



    // ==========================================
    // PREFS / MACRO / CONFIG (merged from VietnameseInputEngine)
    // ==========================================

    var macroStore: MacroStore? = null
    private var macroPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var settingsPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun loadPreferences(context: Context) {
        try {
            AppPreferences.init(context)
            val config = AppPreferences.getEngineConfig()
            applyConfig(config)
            macroStore = MacroRepository(context).loadMacroStore()
            if (macroPrefsListener == null) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (AppPreferences.isMacroDataKey(key)) {
                        reloadMacroStore(context)
                    }
                }
                macroPrefsListener = listener
                AppPreferences.registerMacroPrefsListener(listener)
            }
            if (settingsPrefsListener == null) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                    loadPreferences(context)
                }
                settingsPrefsListener = listener
                AppPreferences.registerSettingsPrefsListener(listener)
            }
        } catch (e: Exception) {
            System.err.println("[VietnameseComposer] Failed to load preferences: ${e.message}")
        }
    }

    fun cleanup() {
        macroPrefsListener?.let {
            AppPreferences.unregisterMacroPrefsListener(it)
            macroPrefsListener = null
        }
        settingsPrefsListener?.let {
            AppPreferences.unregisterSettingsPrefsListener(it)
            settingsPrefsListener = null
        }
    }

    fun reloadMacroStore(context: Context) {
        try {
            AppPreferences.init(context)
            applyConfig(AppPreferences.getEngineConfig())
            macroStore = MacroRepository(context).loadMacroStore()
            reset()
        } catch (e: Exception) {
            System.err.println("[VietnameseComposer] Failed to reload macro store: ${e.message}")
        }
    }

    fun savePreferences(
        context: Context,
        macro: Boolean = options.macroEnabled,
        alwaysMac: Boolean = options.alwaysMacro,
        autoCap: Boolean = autoCapitalize,
        dirW: Boolean = options.directW,
        oldTone: Boolean = options.oldTonePlacement
    ) {
        try {
            AppPreferences.init(context)
            val config = EngineConfig(
                macroEnabled = macro,
                alwaysMacro = alwaysMac,
                autoCapitalize = autoCap,
                directW = dirW,
                oldTonePlacement = oldTone
            )
            AppPreferences.setEngineConfig(config)
            applyConfig(config)
        } catch (e: Exception) {
            System.err.println("[VietnameseComposer] Failed to save preferences: ${e.message}")
        }
    }

    private fun applyConfig(config: EngineConfig) {
        options.macroEnabled = config.macroEnabled
        options.alwaysMacro = config.alwaysMacro
        options.directW = config.directW
        options.oldTonePlacement = config.oldTonePlacement
        autoCapitalize = config.autoCapitalize
    }
}
