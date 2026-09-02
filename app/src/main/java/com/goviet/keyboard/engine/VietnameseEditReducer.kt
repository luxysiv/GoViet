package com.goviet.keyboard.engine

import java.text.BreakIterator

/**
 * Data class representing a contiguous word slice and cursor position at cursor point.
 */
data class WordAtCursor(
    val text: String,
    val startInEditor: Int,
    val endInEditor: Int,
    val cursorOffset: Int
)

/**
 * Thread-safe, allocation-optimized Unicode Grapheme Cluster editor.
 * Handles complex emojis, composite accent marks, surrogate pairs, and extended grapheme clusters
 * without intermediate substring allocations during boundary calculations.
 */
object GraphemeEditor {

    private val threadLocalBreakIterator = ThreadLocal.withInitial {
        BreakIterator.getCharacterInstance()
    }

    private fun getIterator(text: String): BreakIterator {
        val iterator = threadLocalBreakIterator.get() ?: BreakIterator.getCharacterInstance()
        iterator.setText(text)
        return iterator
    }

    /**
     * Finds the index of the grapheme cluster boundary immediately preceding [cursorIndex].
     * Zero-allocation: queries BreakIterator directly on [text] without substrings.
     */
    fun previousBoundary(text: String, cursorIndex: Int): Int {
        if (text.isEmpty() || cursorIndex <= 0) return 0
        val clampedCursor = cursorIndex.coerceIn(0, text.length)
        val iterator = getIterator(text)
        val prev = iterator.preceding(clampedCursor)
        return if (prev != BreakIterator.DONE && prev >= 0) prev else 0
    }

    /**
     * Finds the index of the grapheme cluster boundary immediately succeeding [cursorIndex].
     * Zero-allocation: queries BreakIterator directly on [text] without substrings.
     */
    fun nextBoundary(text: String, cursorIndex: Int): Int {
        if (text.isEmpty() || cursorIndex >= text.length) return text.length
        val clampedCursor = cursorIndex.coerceIn(0, text.length)
        val iterator = getIterator(text)
        val next = iterator.following(clampedCursor)
        return if (next != BreakIterator.DONE && next >= 0) next else text.length
    }

    /**
     * Deletes the grapheme cluster immediately before [cursorIndex].
     * Returns a pair of (newText, newCursorIndex).
     * Slices directly into pre-allocated StringBuilder to avoid intermediate substrings.
     */
    fun deleteBackward(text: String, cursorIndex: Int): Pair<String, Int> {
        if (text.isEmpty() || cursorIndex <= 0) {
            return Pair(text, 0)
        }
        val clampedCursor = cursorIndex.coerceIn(0, text.length)
        val start = previousBoundary(text, clampedCursor)
        if (start >= clampedCursor) {
            return Pair(text, clampedCursor)
        }
        val newLength = text.length - (clampedCursor - start)
        val sb = StringBuilder(newLength)
        sb.append(text, 0, start)
        sb.append(text, clampedCursor, text.length)
        return Pair(sb.toString(), start)
    }

    /**
     * Deletes the last grapheme cluster of [text].
     */
    fun deleteLastGrapheme(text: String): String {
        return deleteBackward(text, text.length).first
    }

    /**
     * Deletes the grapheme cluster immediately after [cursorIndex] (Forward Delete).
     * Returns a pair of (newText, newCursorIndex).
     * Slices directly into pre-allocated StringBuilder to avoid intermediate substrings.
     */
    fun deleteForward(text: String, cursorIndex: Int): Pair<String, Int> {
        if (text.isEmpty() || cursorIndex >= text.length) {
            return Pair(text, cursorIndex.coerceIn(0, text.length))
        }
        val clampedCursor = cursorIndex.coerceIn(0, text.length)
        val end = nextBoundary(text, clampedCursor)
        if (end <= clampedCursor) {
            return Pair(text, clampedCursor)
        }
        val newLength = text.length - (end - clampedCursor)
        val sb = StringBuilder(newLength)
        sb.append(text, 0, clampedCursor)
        sb.append(text, end, text.length)
        return Pair(sb.toString(), clampedCursor)
    }

    /**
     * Computes the character length of the grapheme cluster immediately preceding [cursorIndex].
     * Zero-allocation.
     */
    fun getBackwardGraphemeLength(text: String, cursorIndex: Int = text.length): Int {
        if (text.isEmpty() || cursorIndex <= 0) return 0
        val clampedCursor = cursorIndex.coerceIn(0, text.length)
        return clampedCursor - previousBoundary(text, clampedCursor)
    }

    /**
     * Computes the character length of the grapheme cluster immediately succeeding [cursorIndex].
     * Zero-allocation.
     */
    fun getForwardGraphemeLength(text: String, cursorIndex: Int = 0): Int {
        if (text.isEmpty() || cursorIndex >= text.length) return 0
        val clampedCursor = cursorIndex.coerceIn(0, text.length)
        return nextBoundary(text, clampedCursor) - clampedCursor
    }
}

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

        val nfcWord = VietnameseUnicode.normalizeNfc(word)
        
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

/**
 * Unified reducer for Vietnamese backspace / delete mutations.
 * Single Source of Truth for:
 * - Grapheme cluster deletion
 * - Ownership transitions (e.g. preserving EDITED_LITERAL on delete)
 * - Syllable re-parsing & canonical keystroke generation
 * - Step snapshot generation
 */
object VietnameseEditReducer {

    data class EditResult(
        val display: String,
        val cursorInDisplay: Int,
        val canonicalRaw: String,
        val ownership: CompositionOwnership,
        val parsed: VietnameseLexicalParser.ParsedSyllable?,
        val syllableState: VietnameseComposer.SyllableState,
        val snapshots: List<VietnameseComposer.ComposerSnapshot>
    )

    /**
     * Reduces the current display text and cursor position by deleting the preceding grapheme cluster (Backspace)
     * and reconstructing the syllable state machine accordingly.
     */
    fun reduceBackspace(
        currentDisplay: String,
        cursorInDisplay: Int,
        currentOwnership: CompositionOwnership,
        options: EngineOptions
    ): EditResult {
        if (currentDisplay.isEmpty()) {
            return emptyResult()
        }

        val (newDisplay, newCursor) = GraphemeEditor.deleteBackward(currentDisplay, cursorInDisplay)
        return reduceModifiedText(newDisplay, newCursor, currentOwnership, options)
    }

    /**
     * Reduces the current display text and cursor position by deleting the succeeding grapheme cluster (Forward Delete)
     * and reconstructing the syllable state machine accordingly.
     */
    fun reduceDeleteForward(
        currentDisplay: String,
        cursorInDisplay: Int,
        currentOwnership: CompositionOwnership,
        options: EngineOptions
    ): EditResult {
        if (currentDisplay.isEmpty()) {
            return emptyResult()
        }

        val (newDisplay, newCursor) = GraphemeEditor.deleteForward(currentDisplay, cursorInDisplay)
        return reduceModifiedText(newDisplay, newCursor, currentOwnership, options)
    }

    private fun emptyResult(): EditResult {
        return EditResult(
            display = "",
            cursorInDisplay = 0,
            canonicalRaw = "",
            ownership = CompositionOwnership.LIVE_VIETNAMESE,
            parsed = null,
            syllableState = VietnameseComposer.SyllableState(),
            snapshots = emptyList()
        )
    }

    private fun reduceModifiedText(
        newDisplay: String,
        newCursor: Int,
        currentOwnership: CompositionOwnership,
        options: EngineOptions
    ): EditResult {
        if (newDisplay.isEmpty()) {
            return emptyResult()
        }

        // Ownership transition rule:
        // A literal string remains EDITED_LITERAL when deleting characters,
        // unless deleting at the end produces a confident, valid Vietnamese syllable.
        if (currentOwnership == CompositionOwnership.EDITED_LITERAL) {
            if (newCursor == newDisplay.length && EditedVietnameseRecognizer.canRecompose(newDisplay, options)) {
                val analysis = VietnameseLexicalParser.analyze(newDisplay, options)
                if (analysis.isValid) {
                    val (_, snaps) = VietnameseSnapshotBuilder.generate(analysis, options)
                    return EditResult(
                        display = analysis.display,
                        cursorInDisplay = newCursor,
                        canonicalRaw = analysis.canonicalRaw,
                        ownership = CompositionOwnership.ADOPTED_VIETNAMESE,
                        parsed = analysis.parsed,
                        syllableState = analysis.syllableState,
                        snapshots = snaps
                    )
                }
            }

            val literalState = VietnameseComposer.SyllableState(rawSuffix = newDisplay)
            val literalSnap = VietnameseComposer.ComposerSnapshot(
                literalState,
                newDisplay,
                CompositionOwnership.EDITED_LITERAL
            )
            return EditResult(
                display = newDisplay,
                cursorInDisplay = newCursor,
                canonicalRaw = newDisplay,
                ownership = CompositionOwnership.EDITED_LITERAL,
                parsed = null,
                syllableState = literalState,
                snapshots = listOf(literalSnap)
            )
        }

        val ownership = EditedVietnameseRecognizer.classify(newDisplay, options)
        if (ownership == CompositionOwnership.ADOPTED_VIETNAMESE) {
            val analysis = VietnameseLexicalParser.analyze(newDisplay, options)
            if (analysis.isValid) {
                val (_, snaps) = VietnameseSnapshotBuilder.generate(analysis, options)
                return EditResult(
                    display = analysis.display,
                    cursorInDisplay = newCursor,
                    canonicalRaw = analysis.canonicalRaw,
                    ownership = CompositionOwnership.ADOPTED_VIETNAMESE,
                    parsed = analysis.parsed,
                    syllableState = analysis.syllableState,
                    snapshots = snaps
                )
            }
        }

        // EDITED_LITERAL fallback
        val literalState = VietnameseComposer.SyllableState(rawSuffix = newDisplay)
        val literalSnap = VietnameseComposer.ComposerSnapshot(
            literalState,
            newDisplay,
            CompositionOwnership.EDITED_LITERAL
        )
        return EditResult(
            display = newDisplay,
            cursorInDisplay = newCursor,
            canonicalRaw = newDisplay,
            ownership = CompositionOwnership.EDITED_LITERAL,
            parsed = null,
            syllableState = literalState,
            snapshots = listOf(literalSnap)
        )
    }
}
