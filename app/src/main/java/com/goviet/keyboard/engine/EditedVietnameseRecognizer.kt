package com.goviet.keyboard.engine

data class WordAtCursor(
    val text: String,
    val startInEditor: Int,
    val endInEditor: Int,
    val cursorOffset: Int
)

/**
 * Conservative recognizer for words being edited (via cursor tap or backspace/delete)
 * to distinguish between valid, confident Vietnamese syllables that can be safely recomposed/adopted
 * and Literal words that should remain intact without unintended Telex transformations.
 */
object EditedVietnameseRecognizer {

    /**
     * Checks if a word contains multiple disjoint vowel clusters separated by non-glide consonants (e.g. V-C-V like "ana", "omo", "eva", "user").
     * In a single valid Vietnamese syllable, all nucleus vowels form a single contiguous cluster (except initial 'qu' or 'gi').
     */
    private fun hasDisjointVowelClusters(word: String): Boolean {
        var normalized = word.lowercase()
        // Strip valid initial glides 'qu' and 'gi'
        if (normalized.startsWith("qu") || normalized.startsWith("gi")) {
            normalized = normalized.substring(2)
        }

        var vowelGroupCount = 0
        var inVowel = false
        for (c in normalized) {
            if (VietnameseLexicon.isVowel(c)) {
                if (!inVowel) {
                    vowelGroupCount++
                    inVowel = true
                }
            } else {
                inVowel = false
            }
        }
        return vowelGroupCount > 1
    }

    /**
     * Classifies a candidate word into CompositionOwnership:
     * - ADOPTED_VIETNAMESE: valid, confident Vietnamese syllable structure, safe to recompose.
     * - EDITED_LITERAL: literal or non-standard structure, protect from unintended Telex mutations.
     */
    fun classify(word: String, options: EngineOptions = EngineOptions()): CompositionOwnership {
        if (word.isEmpty()) return CompositionOwnership.LIVE_VIETNAMESE

        val nfcWord = VietnameseCharUtils.normalizeNfc(word)
        
        // 1. Check for suspicious disjoint vowel clusters (V-C-V like "ana", "omo")
        if (hasDisjointVowelClusters(nfcWord)) {
            return CompositionOwnership.EDITED_LITERAL
        }

        // 2. Run lexical parser analysis
        val analysis = VietnameseLexicalParser.analyze(nfcWord, options)
        if (!analysis.isValid) {
            return CompositionOwnership.EDITED_LITERAL
        }

        val parsed = analysis.parsed ?: return CompositionOwnership.EDITED_LITERAL

        // 3. Phonotactic / Coda / Structure conservative checks:
        val onsetLower = parsed.onset.lowercase()
        val codaLower = parsed.coda.lowercase()

        // If coda is present, it MUST be one of the valid Vietnamese codas
        if (codaLower.isNotEmpty() && codaLower !in VietnameseLexicon.CODAS) {
            return CompositionOwnership.EDITED_LITERAL
        }

        // Onset 'w' without tone or special handling (e.g. "war", "warm", "work", "wor", "word")
        // In Vietnamese, 'w' is a Telex shortcut key for 'ư', but as an onset in a completed edited word, it is literal.
        if (onsetLower == "w" || onsetLower.startsWith("w")) {
            return CompositionOwnership.EDITED_LITERAL
        }

        // Non-Vietnamese letters in completed word
        val wordLower = nfcWord.lowercase()
        if (wordLower.any { it in setOf('f', 'j', 'z') }) {
            return CompositionOwnership.EDITED_LITERAL
        }

        // If rawSuffix is not empty, it's not a complete Vietnamese syllable
        if (parsed.rawSuffix.isNotEmpty()) {
            return CompositionOwnership.EDITED_LITERAL
        }

        // Stop consonants ("c", "ch", "p", "t") can only carry ACUTE (sắc) or DOT (nặng) in Vietnamese
        if (codaLower in setOf("c", "ch", "p", "t")) {
            if (parsed.tone != Tone.NONE && parsed.tone != Tone.ACUTE && parsed.tone != Tone.DOT) {
                return CompositionOwnership.EDITED_LITERAL
            }
        }

        return CompositionOwnership.ADOPTED_VIETNAMESE
    }

    fun canRecompose(word: String, options: EngineOptions = EngineOptions()): Boolean {
        return classify(word, options) == CompositionOwnership.ADOPTED_VIETNAMESE
    }
}
