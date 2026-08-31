package com.goviet.keyboard.engine

/**
 * VietnameseLexicalParser:
 * Real-time Lexical Re-composition & Phonological Parsing.
 *
 * Decomposes any Vietnamese word (typed, adopted, or copy-pasted) into its 
 * phonological components: Onset, Nucleus, Coda, Tone, and Suffix.
 *
 * Produces canonical Telex keystrokes and step-by-step composing snapshots,
 * enabling stateless lexical re-composition without ghost word deletions or cursor desyncs.
 */
object VietnameseLexicalParser {

    data class ParsedSyllable(
        val onset: String,
        val nucleus: String,
        val coda: String,
        val tone: Tone,
        val rawSuffix: String = ""
    ) {
        fun toDisplayString(oldTonePlacement: Boolean = false): String {
            val state = VietnameseComposer.SyllableState(
                onset = onset,
                nucleus = nucleus,
                coda = coda,
                tone = tone,
                rawSuffix = rawSuffix
            )
            return state.toDisplayString(oldTonePlacement)
        }

        fun toCanonicalKeystrokes(): String {
            val sb = StringBuilder()

            // 1. Onset
            val onsetLower = onset.lowercase()
            when (onsetLower) {
                "đ" -> sb.append(if (onset == "Đ") "DD" else if (onset[0].isUpperCase()) "Dd" else "dd")
                else -> sb.append(onset)
            }

            // 2. Nucleus
            val nucleusLower = nucleus.lowercase()
            if (nucleusLower == "ươ") {
                val isAllUpper = nucleus.all { it.isUpperCase() }
                val isFirstUpper = nucleus.isNotEmpty() && nucleus[0].isUpperCase()
                sb.append(if (isAllUpper) "UWO" else if (isFirstUpper) "Uwo" else "uwo")
            } else if (nucleusLower == "ưa") {
                val isAllUpper = nucleus.all { it.isUpperCase() }
                val isFirstUpper = nucleus.isNotEmpty() && nucleus[0].isUpperCase()
                sb.append(if (isAllUpper) "UWA" else if (isFirstUpper) "Uwa" else "uwa")
            } else if (nucleusLower == "uơ") {
                val isAllUpper = nucleus.all { it.isUpperCase() }
                val isFirstUpper = nucleus.isNotEmpty() && nucleus[0].isUpperCase()
                sb.append(if (isAllUpper) "UOW" else if (isFirstUpper) "Uow" else "uow")
            } else {
                for (i in nucleus.indices) {
                    val c = nucleus[i]
                    val cl = c.lowercaseChar()
                    val isUpper = c.isUpperCase()
                    when (cl) {
                        'â' -> sb.append(if (isUpper) "Aa" else "aa")
                        'ă' -> sb.append(if (isUpper) "Aw" else "aw")
                        'ê' -> sb.append(if (isUpper) "Ee" else "ee")
                        'ô' -> sb.append(if (isUpper) "Oo" else "oo")
                        'ơ' -> sb.append(if (isUpper) "Ow" else "ow")
                        'ư' -> sb.append(if (isUpper) "Uw" else "uw")
                        else -> sb.append(c)
                    }
                }
            }

            // 3. Coda
            sb.append(coda)

            // 4. Tone
            val toneKey = when (tone) {
                Tone.ACUTE -> 's'
                Tone.GRAVE -> 'f'
                Tone.HOOK -> 'r'
                Tone.TILDE -> 'x'
                Tone.DOT -> 'j'
                Tone.NONE -> null
            }
            if (toneKey != null) {
                val isAllUpper = sb.isNotEmpty() && sb.all { it.isUpperCase() }
                sb.append(if (isAllUpper) toneKey.uppercaseChar() else toneKey)
            }

            // 5. Raw Suffix
            sb.append(rawSuffix)

            return sb.toString()
        }
    }

    data class AnalysisResult(
        val word: String,
        val isValid: Boolean,
        val parsed: ParsedSyllable?,
        val canonicalRaw: String,
        val display: String,
        val syllableState: VietnameseComposer.SyllableState,
        val ownership: CompositionOwnership
    )

    /**
     * Unified analysis: performs Parse, Validate, and Canonical extraction in a single pass.
     * Produces a single shared result containing phonological structure, validity, canonical keystrokes,
     * normalized display string, syllable state, and composition ownership.
     */
    fun analyze(word: String, options: EngineOptions = EngineOptions()): AnalysisResult {
        if (word.isEmpty()) {
            val emptyState = VietnameseComposer.SyllableState()
            return AnalysisResult(
                word = "",
                isValid = false,
                parsed = null,
                canonicalRaw = "",
                display = "",
                syllableState = emptyState,
                ownership = CompositionOwnership.LIVE_VIETNAMESE
            )
        }

        val parsed = parse(word)
        val display = parsed.toDisplayString(options.oldTonePlacement)
        val isValid = parsed.rawSuffix.isEmpty() &&
                VietnameseFiniteStateTable.isValidWord(display) &&
                VietnameseFiniteStateTable.isValidWord(word)

        if (isValid) {
            val canonical = parsed.toCanonicalKeystrokes()
            val finalCanonical = VietnameseCharUtils.applyCasingFromRaw(canonical, word)
            val syllableState = VietnameseComposer.SyllableState(
                onset = parsed.onset,
                nucleus = parsed.nucleus,
                coda = parsed.coda,
                tone = parsed.tone,
                rawSuffix = parsed.rawSuffix
            )
            return AnalysisResult(
                word = word,
                isValid = true,
                parsed = parsed,
                canonicalRaw = finalCanonical,
                display = display,
                syllableState = syllableState,
                ownership = CompositionOwnership.ADOPTED_VIETNAMESE
            )
        } else {
            val literalState = VietnameseComposer.SyllableState(rawSuffix = word)
            return AnalysisResult(
                word = word,
                isValid = false,
                parsed = parsed,
                canonicalRaw = word,
                display = word,
                syllableState = literalState,
                ownership = CompositionOwnership.EDITED_LITERAL
            )
        }
    }

    /**
     * Safely tries to parse a word as a valid Vietnamese syllable.
     * Returns null if the word contains raw suffix or is not phonologically valid in Vietnamese.
     */
    fun tryParseVietnamese(word: String): ParsedSyllable? {
        val res = analyze(word)
        return if (res.isValid) res.parsed else null
    }

    /**
     * Parses any Vietnamese word into its structural syllable components.
     */
    fun parse(word: String): ParsedSyllable {
        if (word.isEmpty()) {
            return ParsedSyllable("", "", "", Tone.NONE, "")
        }

        val nfcWord = VietnameseCharUtils.normalizeNfc(word)
        
        // 1. Extract Tone
        var detectedTone = Tone.NONE
        val untonedChars = StringBuilder()

        for (c in nfcWord) {
            val t = extractToneFromChar(c)
            if (t != Tone.NONE && detectedTone == Tone.NONE) {
                detectedTone = t
            }
            untonedChars.append(VietnameseUnicode.stripTone(c))
        }

        val baseWord = untonedChars.toString()
        val baseLower = baseWord.lowercase()

        // 2. Extract Onset (Longest valid initial consonant)
        var onset = ""
        var remainingAfterOnset = baseWord

        for (cand in VietnameseLexicon.ONSETS) {
            if (baseLower.startsWith(cand)) {
                // Special check for "gi" / "qu"
                if (cand == "gi" && baseLower.length > 2 && VietnameseLexicon.isBaseVowel(baseLower[2])) {
                    onset = baseWord.substring(0, 2)
                    remainingAfterOnset = baseWord.substring(2)
                    break
                } else if (cand == "qu" && baseLower.length > 2 && VietnameseLexicon.isBaseVowel(baseLower[2])) {
                    onset = baseWord.substring(0, 2)
                    remainingAfterOnset = baseWord.substring(2)
                    break
                } else if (cand == "gi" && (baseLower.length == 2 || !VietnameseLexicon.isBaseVowel(baseLower[2]))) {
                    // e.g. "gì", "gìn": onset is 'g', nucleus starts with 'i'
                    onset = baseWord.substring(0, 1)
                    remainingAfterOnset = baseWord.substring(1)
                    break
                } else {
                    onset = baseWord.substring(0, cand.length)
                    remainingAfterOnset = baseWord.substring(cand.length)
                    break
                }
            }
        }

        // 3. Extract Nucleus (contiguous vowels)
        val nucleusSb = StringBuilder()
        var remIdx = 0
        while (remIdx < remainingAfterOnset.length && VietnameseLexicon.isBaseVowel(remainingAfterOnset[remIdx])) {
            nucleusSb.append(remainingAfterOnset[remIdx])
            remIdx++
        }
        val nucleus = nucleusSb.toString()
        val remainingAfterNucleus = remainingAfterOnset.substring(remIdx)
        val remLower = remainingAfterNucleus.lowercase()

        // 4. Extract Coda (Valid final consonant)
        var coda = ""
        var rawSuffix = ""

        if (nucleus.isNotEmpty()) {
            var matchedCoda = false
            for (cand in VietnameseLexicon.CODAS) {
                if (remLower.startsWith(cand)) {
                    val candidateRime = nucleus.lowercase() + cand
                    if (VietnameseFiniteStateTable.isValidPrefix(candidateRime) &&
                        VietnameseFiniteStateTable.isValidToneForRime(candidateRime, detectedTone)) {
                        coda = remainingAfterNucleus.substring(0, cand.length)
                        rawSuffix = remainingAfterNucleus.substring(cand.length)
                        matchedCoda = true
                        break
                    }
                }
            }

            if (!matchedCoda) {
                rawSuffix = remainingAfterNucleus
            }
        } else {
            rawSuffix = remainingAfterNucleus
        }

        val hasValidVietnameseNucleus = nucleus.isNotEmpty() && VietnameseFiniteStateTable.isValidRime(nucleus.lowercase() + coda.lowercase())

        return ParsedSyllable(
            onset = onset,
            nucleus = nucleus,
            coda = coda,
            tone = if (hasValidVietnameseNucleus) detectedTone else Tone.NONE,
            rawSuffix = if (hasValidVietnameseNucleus) rawSuffix else (if (detectedTone != Tone.NONE) word.substring(onset.length) else rawSuffix)
        )
    }

    private fun extractToneFromChar(c: Char): Tone {
        val lower = c.lowercaseChar()
        return when (lower) {
            'á', 'ắ', 'ấ', 'é', 'ế', 'í', 'ó', 'ố', 'ớ', 'ú', 'ứ', 'ý' -> Tone.ACUTE
            'à', 'ằ', 'ầ', 'è', 'ề', 'ì', 'ò', 'ồ', 'ờ', 'ù', 'ừ', 'ỳ' -> Tone.GRAVE
            'ả', 'ẳ', 'ẩ', 'ẻ', 'ể', 'ỉ', 'ỏ', 'ổ', 'ở', 'ủ', 'ử', 'ỷ' -> Tone.HOOK
            'ã', 'ẵ', 'ẫ', 'ẽ', 'ễ', 'ĩ', 'õ', 'ỗ', 'ỡ', 'ũ', 'ữ', 'ỹ' -> Tone.TILDE
            'ạ', 'ặ', 'ậ', 'ẹ', 'ệ', 'ị', 'ọ', 'ộ', 'ợ', 'ụ', 'ự', 'ỵ' -> Tone.DOT
            else -> Tone.NONE
        }
    }

}
