package com.goviet.keyboard.engine

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
        // A literal string remains EDITED_LITERAL when deleting characters.
        if (currentOwnership == CompositionOwnership.EDITED_LITERAL) {
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
