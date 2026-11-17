package com.goviet.keyboard.engine

import com.goviet.keyboard.VietnameseInputMethodService
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputConnection

/**
 * ImeInputConnectionController (IME Input Controller)
 *
 * Architecture Role:
 * - Manages the IME layer interaction with Android's InputConnection.
 * - Delegates the composing preedit to VietnameseComposer's session buffer (single
 *   source of truth); handles cursor tracking, selection, and backspace logic.
 * - Delegates Vietnamese syllable rules and settings (Telex, Simple Telex, Modern Style, Macros) to VietnameseComposer.
 */
class ImeInputConnectionController(
    val service: VietnameseInputMethodService,
    val inputEngine: VietnameseComposer
) {

    private val TAG = "ImeInputConnectionController"

    val backspaceHandler = BackspaceHandler(this)

    enum class TypingMode {
        VIETNAMESE,
        LATIN
    }

    var typingMode: TypingMode = TypingMode.VIETNAMESE
        private set

    fun updateTypingMode(editorInfo: android.view.inputmethod.EditorInfo?) {
        if (editorInfo == null) {
            typingMode = TypingMode.VIETNAMESE
            return
        }
        val inputType = editorInfo.inputType
        if (inputType == android.text.InputType.TYPE_NULL) {
            typingMode = TypingMode.LATIN
            return
        }
        val classType = inputType and android.text.InputType.TYPE_MASK_CLASS
        if (classType == android.text.InputType.TYPE_CLASS_TEXT) {
            val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
            if (variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == 224) {
                typingMode = TypingMode.LATIN
                return
            }
        }
        typingMode = TypingMode.VIETNAMESE
    }

    var lastSetComposingText: String? = null
    private var lastShiftTime = 0L
    var isSelecting: Boolean = false
    var lastKeyPressTime = 0L
    private val displayBuf = OwnedBuffer()
    var composingStartInEditor = -1
    var composingCursorIndex = 0

    /** True while the editor carries a composing region created by us. */
    private var composingSpanActive = false

    /**
     * The caret our own last editor write left behind, or -1 when that write
     * could not know it (no managed range). It answers one cheap question in
     * [onUpdateSelection]: "is this callback the echo of that write?" When it is,
     * the editor is already exactly where the engine cursor is, so both the live
     * caret read and the adoption are skipped — one fewer blocking round-trip on
     * every keystroke, which is what a heavy WebView needs. A callback that does
     * not match it is a real tap or a delayed echo, and keeps the old, verified
     * path.
     */
    private var expectedEditorCaret = -1

    /**
     * A new field is a new editor session: its caret has no relation to the last
     * write's echo and any composing region we tracked belongs to the old field.
     */
    fun resetEditorSession() {
        expectedEditorCaret = -1
        composingSpanActive = false
        composingStartInEditor = -1
    }

    /** TYPE_NULL fields commit key by key; every real text field uses spans. */
    fun usesKeyEventDeletes(): Boolean = isImmediateCommitMode()

    /**
     * "Is this onUpdateSelection ours?" is answered by GEOMETRY, not by a ledger of
     * what we predicted and not by a clock. There is deliberately no TTL, no ring
     * buffer, no re-announce throttle and no "recent typing" window here — the rule
     * itself lives in [reconcileSelection], which sees only these fields:
     *
     *  - [composingStartInEditor] + [lastSetComposingText] delimit the preedit we
     *    own, and every write is one setComposingText over exactly that range, so the
     *    bounds are exact without the editor ever reporting a composing region.
     *
     * A caret inside that range is either the echo of our own write or the user
     * tapping inside their own preedit, and both mean the same thing — "keep
     * composing from here" — so the caret is adopted instead of the buffer being
     * dropped and rewritten (which is what used to make Zalo flash). A caret
     * outside it can only be a real editor-side move, and that is detected by
     * position alone, with no question of how long ago it happened.
     *
     * This replaces a predicted-caret ledger ([expectedCaret]) that also recorded
     * every caret we asked for, in order to absorb the echo of a commit or a
     * delete. It is gone because each of those values duplicated state we already
     * had: a post-commit echo lands after a commit that already cleared the range,
     * and [adoptPrefixAtCaret] declines to re-adopt there anyway (a caret at the
     * end of a word is not a split point). One fewer thing to be stale in the same
     * direction as the range it shadowed.
     */

    /** Shifts the tracked preedit range after text in front of it was deleted. */
    fun shiftPreeditStart(delta: Int) {
        if (delta == 0) return
        if (composingStartInEditor >= 0) composingStartInEditor += delta
        // The delete that follows moves the caret by an amount this call does not
        // track, so the next echo must be verified against the editor, not matched
        // against a prediction.
        expectedEditorCaret = -1
    }

    var userMovedCursor: Boolean = false
    var userSelectedText: Boolean = false

    /**
     * Reacts to an editor selection callback. The rule is [reconcileSelection] —
     * five numbers in, one verdict out — and everything below is the consequence
     * of that verdict, so "is this callback ours?" is answered in one pure place
     * and can be tested without an editor.
     */
    fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        // With a composing span, the editor reports the range itself. That range
        // is authoritative: use it instead of our tracked guess so the echo of a
        // setComposingText is not mistaken for a user tap outside a stale range.
        val candidatesValid = composingSpanActive &&
            candidatesStart >= 0 && candidatesEnd >= candidatesStart
        val preeditStart = if (candidatesValid) candidatesStart else composingStartInEditor
        val preeditLength = if (candidatesValid) candidatesEnd - candidatesStart else lastSetComposingText?.length ?: 0

        val verdict = reconcileSelection(
            newSelStart = newSelStart,
            newSelEnd = newSelEnd,
            composing = inputEngine.isComposing(),
            preeditStart = preeditStart,
            preeditLength = preeditLength
        )

        when (verdict) {
            // The editor said nothing, so there is nothing to move.
            SelectionVerdict.NO_INFO -> return

            // Echo of our own write, or a tap inside our own preedit: keep the
            // composition and adopt the caret, instead of dropping the buffer and
            // rewriting the word from scratch (the old Zalo flicker).
            SelectionVerdict.KEEP_PREEDIT -> {
                userMovedCursor = false
                userSelectedText = false
                if (candidatesValid) composingStartInEditor = candidatesStart
                // The echo of our own write carries exactly the caret that write
                // produced, so the engine cursor is already right and a blocking
                // caret read can be skipped. Any other position is a real tap
                // inside the word and must be adopted.
                if (newSelStart != expectedEditorCaret) {
                    adoptCaretIntoPreedit(newSelStart)
                }
                return
            }

            SelectionVerdict.USER_MOVE, SelectionVerdict.USER_SELECTION -> {
                userMovedCursor = true
                userSelectedText = verdict == SelectionVerdict.USER_SELECTION
                if (inputEngine.isComposing()) {
                    // clearState finishes the composing span: the word the user
                    // moved away from must not stay underlined.
                    clearState()
                }
            }
        }

        // A tap inside committed text adopts the Vietnamese prefix before the
        // caret right away. No typing-window gate: a callback that is genuinely
        // ours never reaches here (the two branches above absorb it), so any
        // callback arriving here is a real editor-side move and must be acted on
        // immediately — otherwise the next keystroke would land at the old caret.
        //
        // The echo of a commit is the one exception: it lands exactly where the
        // commit left the caret, so there is no tap into a word to look for and
        // the text scan would be a blocking round-trip that finds nothing.
        if (newSelStart != expectedEditorCaret) {
            service.currentInputConnection?.let { adoptPrefixAtCaret(it) }
        }
    }

    /**
     * Maps an editor caret that lands inside our preedit back to the engine's raw
     * buffer, so the next keystroke is applied where the user is actually typing.
     */
    private fun adoptCaretIntoPreedit(editorCaret: Int) {
        val display = lastSetComposingText ?: return
        // A delayed echo can carry the caret of an EARLIER write: geometrically
        // inside our range, but behind where the editor actually is now.
        // Adopting that stale position would make the next keystroke insert
        // mid-preedit — wrong character order plus a caret jump — so the live
        // editor caret is the source of truth here (the same reasoning as
        // resolveCompositionAtCursor). The callback value stays as the
        // fallback for editors that won't report a position.
        val liveCaret = service.currentInputConnection?.let { realSelectionStart(it) } ?: -1
        val caret = if (liveCaret >= 0) liveCaret else editorCaret
        val displayOffset = (caret - composingStartInEditor).coerceIn(0, display.length)
        composingCursorIndex = rawIndexOfDisplay(
            inputEngine.composingRaw(), display, displayOffset, inputEngine.composeAsVietnamese
        )
        // The editor caret is now known to be [caret]; the next echo of the same
        // position therefore needs no second read.
        expectedEditorCaret = caret
    }

    /**
     * Adopts the Vietnamese prefix before the caret right away when the user taps
     * into the middle of a word. The preedit then spans only [start, prefix) —
     * the remainder of the word stays committed outside — so the caret keeps its
     * exact position and every later edit (typing, backspace, delete) happens on
     * the display text through one unified path. Public so backspace can also ask
     * for the adoption when an editor does not report the tap via onUpdateSelection.
     *
     * Only a caret strictly INSIDE a word may adopt. A caret sitting at a word end
     * must not turn that word back into a preedit: right after a separator, a
     * punctuation commit or a finished preedit, the word in front of the caret is
     * exactly the one the user just left, and re-adopting it would make the next
     * keystroke rewrite it. A key typed at a word end adopts through
     * [resolveCompositionAtCursor] instead, which checks whether the key is a tone
     * or vowel modifier before taking the word over.
     */
    fun adoptPrefixAtCaret(ic: InputConnection) {
        if (inputEngine.isComposing()) return
        if (userSelectedText) return
        if (service._languageMode.value == "ENG" || isBypassVietnameseComposing()) return

        val word = findWordAroundCursor(ic) ?: return
        if (word.text.isEmpty()) return
        val offset = word.cursorOffset
        if (offset <= 0 || offset >= word.text.length) return
        if (word.startInEditor < 0 || word.endInEditor <= word.startInEditor) return
        if (word.endInEditor - word.startInEditor != word.text.length) return

        val prefix = word.text.substring(0, offset)
        if (!EditedVietnameseRecognizer.canRecompose(word.text)) return
        val canonical = inputEngine.adoptRoundTrip(prefix) ?: return

        composingStartInEditor = word.startInEditor
        inputEngine.composeAsVietnamese = true
        inputEngine.setComposingRaw(canonical)
        composingCursorIndex = canonical.length
        lastSetComposingText = prefix
        // Take ownership of the existing characters without rewriting them: the
        // editor will now report this range as the composing region.
        markComposingRegion(ic, word.startInEditor, prefix.length)
        expectedEditorCaret = word.startInEditor + prefix.length
        userMovedCursor = false
    }

    data class MacroExpansionRecord(val trigger: String, val expandedText: String, val timestamp: Long)
    var lastExpandedMacro: MacroExpansionRecord? = null

    private fun recordImeCommit(word: String) {
        val trimmed = word.trim()
        if (trimmed.isNotEmpty()) {
            service.lastCommittedWord = VietnameseUnicode.normalizeNfc(trimmed)
        }
    }

    /** Ends our composing region without touching the text it contains. */
    private fun finishComposingSpan(ic: InputConnection) {
        if (!composingSpanActive) return
        ic.finishComposingText()
        composingSpanActive = false
    }

    /**
     * [finishSpan] is false only from the commit path, which needs to replace or
     * finish the region itself after the engine state has been cleared.
     */
    fun clearState(ic: InputConnection? = null, finishSpan: Boolean = true) {
        if (finishSpan) {
            (ic ?: service.currentInputConnection)?.let { finishComposingSpan(it) }
        }
        inputEngine.reset()
        lastSetComposingText = null
        lastKeyPressTime = 0L
        composingStartInEditor = -1
        composingCursorIndex = 0
        expectedEditorCaret = -1
        lastExpandedMacro = null
    }

    fun composeAsVietnameseLetterChar(c: Char): Boolean {
        if (c.isDigit()) return false
        if (c.isLetter()) return true
        val type = Character.getType(c)
        return type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
    }

    /**
     * The TRUE caret position, as the editor reports it right now.
     *
     * Our own edits move the caret, and onUpdateSelection echoes of them can be
     * delayed by a frame (a space commit followed immediately by a backspace is
     * the classic case). A remembered caret from such an echo is a guess; the
     * editor's own answer is not, so a guess is never used as a substitute:
     * when the editor does not answer, this returns -1 and every caller treats
     * that as "no information" and declines to act, instead of writing a preedit
     * at a position nobody verified.
     */
    private fun realSelectionStart(ic: InputConnection): Int =
        queryExtractedText(ic)?.selectionStart ?: -1

    private fun queryExtractedText(ic: InputConnection): android.view.inputmethod.ExtractedText? {
        val request = android.view.inputmethod.ExtractedTextRequest()
        request.token = 0
        return ic.getExtractedText(request, 0)
    }

    /**
     * True when the editor has a real selection right now — used by the delete
     * paths, where answering "no" wrongly would delete one grapheme INSIDE a
     * selection the user made. getSelectedText is the API for exactly that
     * question (null for a collapsed caret), so no remembered selection offset
     * is involved.
     */
    fun hasRealSelection(ic: InputConnection): Boolean {
        if (isSelecting) return true
        if (userSelectedText) return true
        return !ic.getSelectedText(0).isNullOrEmpty()
    }

    fun findWordAroundCursor(ic: InputConnection, caret: Int = realSelectionStart(ic)): WordAtCursor? {
        // Absolute offsets are only meaningful relative to the caret, and the
        // caret is the editor's to tell us: without it there is no word to
        // describe, and guessing position 0 would hand back a range pointing at
        // the start of the document. Checked before the text reads so a field that
        // will not report a position costs no text round-trips at all.
        if (caret < 0) return null

        val beforeText = ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
        val afterText = ic.getTextAfterCursor(64, 0)?.toString() ?: ""

        var i = beforeText.length - 1
        while (i >= 0 && composeAsVietnameseLetterChar(beforeText[i])) {
            i--
        }
        val wordBefore = beforeText.substring(i + 1)

        var j = 0
        while (j < afterText.length && composeAsVietnameseLetterChar(afterText[j])) {
            j++
        }
        val wordAfter = afterText.substring(0, j)

        val fullWord = wordBefore + wordAfter
        if (fullWord.isEmpty()) return null

        return WordAtCursor(
            text = fullWord,
            startInEditor = (caret - wordBefore.length).coerceAtLeast(0),
            endInEditor = caret + wordAfter.length,
            cursorOffset = wordBefore.length
        )
    }

    /**
     * Applies one Telex key to the live composition buffer at the tracked caret.
     *
     * Returns true when the key had to START A NEW SYLLABLE in front of the
     * running preedit, i.e. the caret sits before the preedit's onset
     * ([composingCursorIndex] == 0 while the buffer is not empty).
     *
     * This split is mandatory, not an optimisation. The kernel models exactly
     * ONE syllable per buffer ([VietnameseComposer.SyllableState] holds a single
     * onset/nucleus/coda), so gluing a new key onto raw index 0 would append a
     * second syllable to that buffer: every letter behind the caret then falls
     * out of the nucleus into the raw suffix, the tone mark is dropped and the
     * trailing tone key leaks out as a literal character. Typing "as" before the
     * "t" of the preedit "thấy" would render "áthaays" instead of "áthấy".
     *
     * Instead the running preedit is dropped from the BUFFER only — the editor
     * text is left exactly as it is, character for character — and the new
     * syllable is written as a pure insertion at the caret (see [writePreedit],
     * whose range is empty here). Every keystroke therefore transforms only the
     * syllable under the caret and never its neighbours.
     */
    internal fun applyKeyToComposingBuffer(key: Char, ic: InputConnection? = null): Boolean {
        if (inputEngine.isComposing() && composingCursorIndex <= 0 && inputEngine.composingRawLength() > 0) {
            // The old syllable stays in the editor, but its span must end before a
            // new syllable is inserted at the caret.
            (ic ?: service.currentInputConnection)?.let { finishComposingSpan(it) }
            inputEngine.reset()
            inputEngine.composeAsVietnamese = true
            composingCursorIndex = 0
            lastSetComposingText = null
            inputEngine.insertComposingKey(0, key)
            composingCursorIndex = 1
            return true
        }
        // A tone key edits the SYLLABLE, not the raw stream at the caret.
        // Splicing it into an already-transformed word is what produced
        // "đosnosgf" from "đồng": a raw like "ddonsgf" has its early 's' folded
        // into the vowel's shape, so a display→raw mapping fallback inserts the
        // new 's' in the wrong slot. Retone from the displayed syllable instead
        // so the word the user sees is kept and only its tone changes.
        if (VietnameseComposer.isToneKey(key.lowercaseChar()) &&
            inputEngine.isComposing() && inputEngine.composeAsVietnamese &&
            composingCursorIndex > 0 && !isImmediateCommitMode()
        ) {
            val displayOffset = displayCursorIndex()
            val display = lastSetComposingText ?: ""
            if (display.isNotEmpty() && inputEngine.retoneComposing(display, key)) {
                // lastSetComposingText is left for updateComposingUI to advance,
                // as with every other buffer mutation.
                composingCursorIndex = rawIndexOfDisplay(
                    inputEngine.composingRaw(), compileComposingText(), displayOffset,
                    inputEngine.composeAsVietnamese
                )
                return false
            }
        }
        splitLiteralTailAtCaret(ic)
        inputEngine.insertComposingKey(composingCursorIndex, key)
        composingCursorIndex += 1
        return false
    }

    /**
     * Detaches the locked literal tail sitting behind the caret so the syllable
     * under it can be edited on its own.
     *
     * The kernel re-segments its WHOLE raw buffer on every keystroke, so a
     * reverted Telex key stays in the raw and keeps shadowing the next one:
     * "err" renders "er" (raw suffix "r"), and typing "s" between the "e" and
     * the "r" inserts at raw index 1, producing "esrr" — which the kernel
     * re-segments straight back to "er". The new key is swallowed by the
     * cancelled "r" pair instead of applying to the "e".
     *
     * When the caret sits exactly on the boundary between the syllable and that
     * locked tail, the syllable prefix is re-adopted as a FRESH composition and
     * the tail is left committed in the editor, outside the managed range. The
     * tail is then invisible to the kernel, so the next key transforms only the
     * syllable ("er" → "é", editor "ér") and the "r" stays put.
     *
     * The split is deliberately narrow. It fires only while the caret is
     * strictly inside the preedit AND the remainder is a locked tail
     * ([VietnameseComposer.literalTailLength] > 0) that the syllable prefix can
     * round-trip. A caret at the end of the preedit appends as before, and a
     * preedit with no locked tail (e.g. "thấy") is never split — which is what
     * keeps a mid-onset insert inside the same syllable.
     */
    private fun splitLiteralTailAtCaret(ic: InputConnection? = null) {
        if (isImmediateCommitMode()) return
        if (!inputEngine.composeAsVietnamese) return
        val rawLength = inputEngine.composingRawLength()
        if (composingCursorIndex <= 0 || composingCursorIndex >= rawLength) return
        val tailLength = inputEngine.literalTailLength()
        if (tailLength <= 0) return
        val display = lastSetComposingText ?: return
        val syllableLength = display.length - tailLength
        if (syllableLength <= 0) return
        if (displayCursorIndex() != syllableLength) return
        val prefix = display.substring(0, syllableLength)
        val canonical = inputEngine.adoptRoundTrip(prefix) ?: return
        inputEngine.setComposingRaw(canonical)
        composingCursorIndex = canonical.length
        lastSetComposingText = prefix
        // The locked tail stays in the editor as committed text. Shrink the
        // composing span to the prefix so the next write cannot swallow it.
        val start = composingStartInEditor
        if (start >= 0) (ic ?: service.currentInputConnection)?.setComposingRegion(start, start + prefix.length)
    }

    /**
     * Marks already committed text as composing instead of rewriting it. This is
     * what a span-based IME is for: the editor keeps the characters, we only take
     * ownership of the range so the next Telex key can diff it.
     */
    private fun markComposingRegion(ic: InputConnection, start: Int, length: Int) {
        if (start < 0 || length <= 0) return
        composingStartInEditor = start
        ic.setComposingRegion(start, start + length)
        composingSpanActive = true
    }

    /**
     * Dedicated cursor→engine bridge: decides how a fresh keystroke starts a composition
     * session relative to existing committed text at the editor caret.
     */
    private fun resolveCompositionAtCursor(
        ic: InputConnection,
        key: String,
        wordAtCursor: WordAtCursor?,
        caret: Int
    ) {
        // -1 when the editor will not say: no managed range is claimed, so the
        // preedit is written as a plain insertion at the editor's own caret.
        composingStartInEditor = caret
        composingCursorIndex = 0
        // Adoption never moves the editor caret: whatever range is claimed below,
        // the caret stays exactly where the editor put it, so its next echo can be
        // recognised without a read.
        expectedEditorCaret = caret

        val wordCursorOffset = wordAtCursor?.cursorOffset ?: 0
        val wordText         = wordAtCursor?.text ?: ""
        val wordTextLength   = wordText.length

        val isAtEnd = wordAtCursor != null && wordCursorOffset == wordTextLength

        val adoptTarget = if (isAtEnd) wordText else wordText.substring(0, wordCursorOffset)

        val adoptResult = if (adoptTarget.isNotEmpty() && EditedVietnameseRecognizer.canRecompose(adoptTarget)) {
            inputEngine.adoptWord(adoptTarget)
        } else null

        val onsetEnd = adoptResult?.onsetLength ?: 0
        val isAtOrAfterVowel = adoptResult != null && adoptResult.isValid && wordCursorOffset >= onsetEnd + 1

        val lowerKey = if (key.isNotEmpty()) key[0].lowercaseChar() else ' '
        val isTone = VietnameseComposer.isToneKey(lowerKey)
        val isVowelMod = VietnameseComposer.isVowelModifierKey(lowerKey)

        val shouldAdopt = wordAtCursor != null && adoptResult != null && adoptResult.isValid && (
            isAtEnd || (isAtOrAfterVowel && (isTone || isVowelMod))
        )

        val regionValid = wordAtCursor != null &&
                wordAtCursor.startInEditor >= 0 &&
                wordAtCursor.endInEditor > wordAtCursor.startInEditor &&
                (wordAtCursor.endInEditor - wordAtCursor.startInEditor) == wordAtCursor.text.length

        if (shouldAdopt && regionValid) {
            val canonicalRaw = inputEngine.canonicalRawIfRoundTrips(adoptResult, adoptTarget)
            if (canonicalRaw == null) {
                inputEngine.composeAsVietnamese = true
                userMovedCursor = false
                return
            }
            composingStartInEditor = wordAtCursor.startInEditor
            inputEngine.composeAsVietnamese = true
            inputEngine.setComposingRaw(canonicalRaw)
            composingCursorIndex = canonicalRaw.length
            lastSetComposingText = adoptTarget
            markComposingRegion(ic, wordAtCursor.startInEditor, adoptTarget.length)
            userMovedCursor = false
            return
        }

        val composeAsVietnameseOnsetSeed = OnsetMap.ALL_ONSETS.contains(wordText.lowercase())
        if (wordAtCursor != null && isAtEnd && wordText.isNotEmpty() && composeAsVietnameseOnsetSeed && regionValid) {
            composingStartInEditor = wordAtCursor.startInEditor
            inputEngine.composeAsVietnamese = true
            inputEngine.setComposingRaw(wordText)
            composingCursorIndex = wordText.length
            lastSetComposingText = wordText
            markComposingRegion(ic, wordAtCursor.startInEditor, wordText.length)
            userMovedCursor = false
            return
        }
        if (wordAtCursor != null && isAtEnd && wordText.isNotEmpty() && !composeAsVietnameseOnsetSeed && regionValid) {
            composingStartInEditor = wordAtCursor.startInEditor
            inputEngine.composeAsVietnamese = false
            inputEngine.setComposingRaw(wordText)
            composingCursorIndex = wordText.length
            lastSetComposingText = wordText
            markComposingRegion(ic, wordAtCursor.startInEditor, wordText.length)
            userMovedCursor = false
            return
        }

        inputEngine.composeAsVietnamese = !(wordAtCursor != null && wordCursorOffset > 0 && wordCursorOffset < wordTextLength)

        userMovedCursor = false
    }

    /**
     * The one deliberate exception to "every edit is a composing span": TYPE_NULL
     * fields (terminal-style / hardware-key inputs) have no text of their own to
     * preedit, so they get committed key by key with backspace key events instead
     * of a managed range. It is a field-type decision, not a legacy-Android
     * fallback, and it never runs for normal text fields.
     */
    fun isImmediateCommitMode(): Boolean {
        val editorInfo = service.currentInputEditorInfo ?: return false
        return editorInfo.inputType == android.text.InputType.TYPE_NULL
    }

    private fun isBypassVietnameseComposing(): Boolean {
        return typingMode == TypingMode.LATIN
    }

    private fun composeAsVietnameseComposingKey(key: String): Boolean {
        if (service._languageMode.value == "ENG") return false
        if (isBypassVietnameseComposing()) return false
        if (key.length != 1) return false
        val char = key[0]
        return char in 'a'..'z' || char in 'A'..'Z' || char.lowercaseChar() != char.uppercaseChar()
    }

    /* =========================================================================
     * COMPOSING BUFFER & UI LIFECYCLE
     * ========================================================================= */

    fun resetComposingUI(ic: InputConnection, backspaceCountIfImmediate: Int = 0) {
        val lastStr = lastSetComposingText ?: ""
        val start = composingStartInEditor
        lastSetComposingText = null
        inputEngine.reset()
        composingStartInEditor = -1
        if (isImmediateCommitMode()) {
            if (backspaceCountIfImmediate > 0) {
                backspaceHandler.sendBackspaceEvents(ic, backspaceCountIfImmediate)
            }
        } else if (composingSpanActive) {
            // Emptying the composing region removes exactly the text we own, then
            // finish leaves the editor with the caret where the preedit began.
            ic.setComposingText("", 1)
            ic.finishComposingText()
            composingSpanActive = false
        } else if (lastStr.isNotEmpty() && start >= 0) {
            // Defensive fallback for a preedit whose span was never established.
            ic.setSelection(start + lastStr.length, start + lastStr.length)
            ic.deleteSurroundingText(lastStr.length, 0)
        }
        // The preedit is gone and the caret sits where it began, so the echo of
        // this delete needs no read either.
        expectedEditorCaret = if (start >= 0) start else -1
    }

    /**
     * Standard Android IME write path. The editor owns the diff inside the
     * composing region, so there is no read-back, no verdict to stick and no
     * fallback delete that a rich web editor could ignore.
     */
    private fun writeComposingPreedit(ic: InputConnection, display: String) {
        val previous = lastSetComposingText ?: ""
        var start = composingStartInEditor
        val caretInDisplay = displayCursorIndex()
        lastSetComposingText = display

        if (start < 0) {
            // We own no range yet. The caret is where this text is about to be
            // inserted (or where the previous delivery ended), so one read at
            // this boundary is enough to make cursor/adopt logic exact later.
            val caret = realSelectionStart(ic)
            if (caret >= 0) start = caret - previous.length
            composingStartInEditor = start
        }

        ic.setComposingText(display, 1)
        composingSpanActive = display.isNotEmpty()
        if (start >= 0 && caretInDisplay != display.length) {
            ic.setSelection(start + caretInDisplay, start + caretInDisplay)
        }
        expectedEditorCaret = if (start >= 0) start + caretInDisplay else -1
    }

    /** The only preedit write path: Android's composing-span contract. */
    fun writePreedit(ic: InputConnection, display: String) = writeComposingPreedit(ic, display)

    /**
     * Claims a managed range for a preedit that was written as a plain insertion
     * at the editor's caret, so the write that produced it is recognised as ours
     * by [reconcileSelection] instead of looking like a user move.
     *
     * Only for the paths that cannot know the position beforehand: a preedit
     * written where the editor's caret already was (the macro rollback) leaves
     * the range unclaimed, and its own echo would then arrive as a real move and
     * drop the buffer the call was asked to restore. The position is read from
     * the editor rather than predicted, and only on this rare path — inside the
     * caller's batch edit, so it costs no extra round-trip. If the editor does
     * not answer, no range is claimed and the next keystroke writes the preedit
     * as an insertion again.
     */
    fun claimPreeditRangeAtCaret(ic: InputConnection, displayLength: Int) {
        val caret = realSelectionStart(ic)
        if (caret >= 0) {
            composingStartInEditor = caret - displayLength
            expectedEditorCaret = caret
        }
    }

    /**
     * Rewrites the preedit display. Runs exclusively inside [handleKeyPress]'s
     * single beginBatchEdit, so it must NOT open its own batch — a nested
     * beginBatchEdit/endBatchEdit pair costs two extra binder round-trips to the
     * editor on every keystroke for zero atomicity gain.
     */
    fun updateComposingUI(ic: InputConnection, lastLenIfImmediate: Int = 0, explicitCompiled: String? = null) {
        val compiled = explicitCompiled ?: compileComposingText()
        val oldStr = lastSetComposingText ?: ""
        val oldLen = oldStr.length
        if (isImmediateCommitMode()) {
            lastSetComposingText = compiled
            if (oldStr.isNotEmpty() && compiled.length == oldLen - 1 && oldStr.startsWith(compiled)) {
                backspaceHandler.sendBackspaceEvents(ic, 1)
            } else if (lastLenIfImmediate > 0) {
                backspaceHandler.sendBackspaceEvents(ic, lastLenIfImmediate)
            }
            ic.commitText(compiled, 1)
        } else {
            writePreedit(ic, compiled)
        }
    }

    private fun handleBackspace(ic: InputConnection) {
        backspaceHandler.handleBackspace(ic)
    }

    private fun handleDeleteForward(ic: InputConnection) {
        backspaceHandler.handleDeleteForward(ic)
    }

    private fun handleDeleteWord(ic: InputConnection) {
        backspaceHandler.handleDeleteWord(ic)
    }

    private fun handleSeparator(ic: InputConnection, separator: String) {
        // Runs inside handleKeyPress's batch, so the commit and the separator are
        // one editor transaction: no frame in which the word and its space are
        // written apart.
        if (inputEngine.isComposing()) {
            commitAndReset(wordBreak = separator, alreadyBatched = true)
        } else {
            ic.commitText(separator, 1)
        }
        if (separator != " ") {
            recordImeCommit(separator)
        }
        service.notifySentenceStateAfterKey(separator)
        service.evaluateAutoShift(forceIpc = false)
    }

    fun handleKeyPress(key: String) {
        val now = System.currentTimeMillis()
        lastKeyPressTime = now
        val ic: InputConnection? = service.currentInputConnection
        if (ic == null) {
            return
        }

        if (key != "BACKSPACE") {
            lastExpandedMacro = null
        }

        ic.beginBatchEdit()
        try {
            val isTelexMode = service._languageMode.value != "ENG" && !isBypassVietnameseComposing()
            if (key == "SPACE") {
                handleSeparator(ic, " ")
                return
            } else if (key == "ENTER") {
                commitAndReset(alreadyBatched = true)
                val editorInfo = service.currentInputEditorInfo
                val inputType = editorInfo?.inputType ?: 0
                val isMultiLine = (inputType and android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_TEXT &&
                        ((inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
                         (inputType and android.text.InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0)
                val imeOptions = editorInfo?.imeOptions ?: 0
                val actionMasked = imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION
                val hasNoEnterAction = (imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

                if (!isMultiLine && !hasNoEnterAction && actionMasked != android.view.inputmethod.EditorInfo.IME_ACTION_NONE && actionMasked != android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(actionMasked)
                } else if (!isMultiLine && !hasNoEnterAction && editorInfo?.actionId != 0 && editorInfo?.actionId != null) {
                    ic.performEditorAction(editorInfo.actionId)
                } else {
                    sendKeyEvent(ic, KeyEvent.KEYCODE_ENTER)
                }
                service.notifySentenceStateAfterKey("ENTER")
                service.evaluateAutoShift(forceIpc = false)
                return
            } else if (BoundaryClassifier.isBoundary(key)) {
                handleSeparator(ic, key)
                return
            }

            when (key) {
                "BACKSPACE" -> handleBackspace(ic)
                "DELETE", "FORWARD_DELETE" -> handleDeleteForward(ic)
                "DELETE_WORD" -> handleDeleteWord(ic)
                "PASTE_OTP" -> {
                    val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val primaryClip = clipboard.primaryClip
                    if (primaryClip != null && primaryClip.itemCount > 0) {
                        val text = primaryClip.getItemAt(0).text?.toString() ?: ""
                        val otpRegex = "\\d{4,8}".toRegex()
                        val match = otpRegex.find(text)
                        val otp = match?.value ?: text.filter { it.isDigit() }.take(6)
                        // Through insertLiteral, not ic.commitText: this is the
                        // one key that pasted outside the keyboard's own
                        // transaction, so whatever composing region the field
                        // had stayed behind and the IME was left describing a
                        // preedit it no longer owned.
                        insertLiteral(otp)
                        if (otp.isNotEmpty()) {
                            service.notifySentenceStateAfterKey("PASTE_OTP")
                        }
                    }
                }
                "SHIFT" -> {
                    val shiftNow = System.currentTimeMillis()
                    lastShiftTime = service.shiftController.toggleShiftKey(shiftNow, lastShiftTime)
                }
                "SHIFT_LONG" -> {
                    service.shiftController.forceCapsLock()
                }
                else -> {
                    val actualKey = if (service.shiftController.isShifted && key.length == 1 && key[0].isLetter()) {
                        key.uppercase()
                    } else {
                        key
                    }
                    service.notifySentenceStateAfterKey(actualKey)
                    if (!composeAsVietnameseComposingKey(key)) {
                        commitAndReset(alreadyBatched = true)
                        ic.commitText(actualKey, 1)
                        service.lastCommittedWord = actualKey
                        service.shiftController.consumeSingleShift()
                        service.evaluateAutoShift(forceIpc = false)
                    } else {
                        if (userMovedCursor && inputEngine.isComposing()) {
                            // Caret was dragged mid-compose: the committed
                            // preedit stays as-is; drop the buffer so this
                            // keypress re-adopts at the real caret instead of
                            // rewriting around a stale position.
                            clearState()
                        }
                        if (!inputEngine.isComposing()) {
                            // One caret read, shared by the word scan and the
                            // resolver — the resolver used to ask again.
                            val caret = realSelectionStart(ic)
                            resolveCompositionAtCursor(ic, actualKey, findWordAroundCursor(ic, caret), caret)
                        }
                        val lastLen = lastSetComposingText?.length ?: 0
                        applyKeyToComposingBuffer(actualKey[0], ic)

                        val casedDisplay = if (inputEngine.composeAsVietnamese) {
                            inputEngine.toDisplayString()
                        } else {
                            compileRawDisplay()
                        }

                        updateComposingUI(ic, lastLen, casedDisplay)

                        if (isImmediateCommitMode()) {
                            recordImeCommit(casedDisplay)
                        }
                        service.shiftController.consumeSingleShift()
                    }
                }
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun sendKeyEvent(ic: InputConnection, keyCode: Int, isShifted: Boolean = false) {
        if (isShifted) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
        }
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        if (isShifted) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT))
        }
    }

    private fun sendMoveKey(ic: InputConnection, keycode: Int) {
        sendKeyEvent(ic, keycode, isSelecting)
    }

    fun handleEditAction(action: String) {
        val ic = service.currentInputConnection ?: return
        when (action) {
            "LEFT" -> sendMoveKey(ic, KeyEvent.KEYCODE_DPAD_LEFT)
            "RIGHT" -> sendMoveKey(ic, KeyEvent.KEYCODE_DPAD_RIGHT)
            "UP" -> sendMoveKey(ic, KeyEvent.KEYCODE_DPAD_UP)
            "DOWN" -> sendMoveKey(ic, KeyEvent.KEYCODE_DPAD_DOWN)
            "HOME" -> sendMoveKey(ic, KeyEvent.KEYCODE_MOVE_HOME)
            "END" -> sendMoveKey(ic, KeyEvent.KEYCODE_MOVE_END)
            "TOGGLE_SELECT" -> {
                isSelecting = !isSelecting
            }
            "SELECT_ALL" -> ic.performContextMenuAction(android.R.id.selectAll)
            "COPY" -> ic.performContextMenuAction(android.R.id.copy)
            "PASTE" -> {
                ic.performContextMenuAction(android.R.id.paste)
                isSelecting = false
            }
            "CUT" -> {
                ic.performContextMenuAction(android.R.id.cut)
                isSelecting = false
            }
            "DELETE" -> {
                // Batched like BackspaceHandler's paths: the selection delete and
                // the forward delete are one editor transaction, and a DELETE key
                // must cost the same single round-trip as BACKSPACE.
                ic.beginBatchEdit()
                try {
                    val selected = ic.getSelectedText(0)
                    if (selected != null && selected.isNotEmpty()) {
                        clearState()
                        ic.commitText("", 1)
                    } else {
                        if (isImmediateCommitMode()) {
                            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL))
                            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_FORWARD_DEL))
                        } else {
                            backspaceHandler.deleteNextGraphemeOrChar(ic)
                        }
                    }
                } finally {
                    ic.endBatchEdit()
                }
            }
        }
    }

    fun compileComposingText(): String = compileRawDisplay()

    /**
     * Single derivation path: display, commit and casing all flow through this
     * function, so what the user sees is always exactly what gets committed.
     */
    fun compileRawDisplay(): String {
        if (!inputEngine.isComposing()) return ""
        inputEngine.toDisplayBuffer(displayBuf)
        return VietnameseUnicode.applyCasingFromRaw(displayBuf, inputEngine.composingRaw())
    }

    /** Display caret offset (chars) for the current raw caret — zero-alloc. */
    fun displayCursorIndex(): Int {
        val raw = inputEngine.composingRaw()
        val end = composingCursorIndex.coerceIn(0, inputEngine.composingRawLength())
        if (end <= 0) return 0
        inputEngine.compileRawInto(raw, inputEngine.composeAsVietnamese, displayBuf, end)
        return displayBuf.len
    }

    /**
     * Maps a display offset back to the raw buffer offset. Used after display-level
     * edits (backspace/delete + re-adoption) where the canonical raw no longer maps
     * 1:1 to display characters (e.g. "â" is one grapheme but two raw keys "aa").
     */
    fun rawIndexOfDisplay(
        raw: CharSequence,
        display: String,
        displayOffset: Int,
        vietnamese: Boolean
    ): Int {
        if (displayOffset <= 0) return 0
        if (displayOffset >= display.length) return raw.length
        if (!vietnamese) return displayOffset.coerceAtMost(raw.length)
        // A raw prefix need not render exactly like the prefix of the final
        // display: a tone key can change the vowel's shape ("ddons" renders
        // "ốn" while the finished word shows "đồng"), and a fold-last raw
        // keeps the fold key between the caret and the vowel it modifies. The
        // raw index is therefore found in three passes, loosening the compare
        // only when the stricter one finds nothing — never falling straight
        // back to the display offset, which is wrong for multi-key syllables.
        for (pass in 0..2) {
            for (i in 0..raw.length) {
                inputEngine.compileRawInto(raw, inputEngine.composeAsVietnamese, displayBuf, i)
                if (displayPrefixMatches(displayBuf, display, displayOffset, pass)) return i
            }
        }
        return displayOffset.coerceAtMost(raw.length)
    }

    private fun displayPrefixMatches(buf: OwnedBuffer, display: String, len: Int, pass: Int): Boolean {
        if (buf.len != len) return false
        for (j in 0 until len) {
            val a = buf[j]
            val b = display[j]
            val matches = when (pass) {
                0 -> a == b
                1 -> VietnameseUnicode.stripTone(a) == VietnameseUnicode.stripTone(b)
                else -> VietnameseUnicode.stripDiacritics(a) == VietnameseUnicode.stripDiacritics(b)
            }
            if (!matches) return false
        }
        return true
    }

    fun compileText(raw: String): String {
        if (raw.isEmpty()) return ""
        inputEngine.compileRawInto(raw, vietnamese = true, displayBuf)
        return VietnameseUnicode.applyCasingFromRaw(displayBuf, raw)
    }

    private fun tryExpandMacro(raw: String, wordBreak: String): String? {
        if (!inputEngine.macroEnabled) return null
        val store = inputEngine.macroStore ?: return null
        if (store.isEmpty()) return null

        store.lookup(raw.lowercase())?.let { expansion ->
            return applyMacroCase(expansion, raw) + wordBreak
        }

        val composed = compileText(raw)
        if (composed != raw) {
            store.lookup(composed.lowercase())?.let { expansion ->
                return applyMacroCase(expansion, composed) + wordBreak
            }
        }
        return null
    }

    private fun applyMacroCase(expansion: String, typed: String): String =
        if (typed.isNotEmpty() && typed.all { it.isUpperCase() }) expansion.uppercase() else expansion

    /**
     * Commits the running preedit and drops it.
     *
     * [alreadyBatched] must be true when the caller already opened a batch edit
     * ([handleKeyPress] does): a nested beginBatchEdit/endBatchEdit pair costs two
     * extra binder round-trips to the editor and buys no extra atomicity.
     */
    fun commitAndReset(wordBreak: String = "", alreadyBatched: Boolean = false) {
        commitAndResetTo(service.currentInputConnection, wordBreak, alreadyBatched)
    }

    /** Exposed for tests that own their InputConnection rather than the service's. */
    internal fun commitAndResetTo(ic: InputConnection?, wordBreak: String, alreadyBatched: Boolean) {
        if (!inputEngine.isComposing()) return
        if (ic == null) {
            clearState()
            return
        }
        if (!alreadyBatched) ic.beginBatchEdit()
        try {
            val raw = inputEngine.composingRaw().toString()
            val macroExpanded = tryExpandMacro(raw, wordBreak)
            val outputText = macroExpanded
                ?: (if (!inputEngine.composeAsVietnamese) raw + wordBreak else compileRawDisplay() + wordBreak)
            val start = composingStartInEditor
            val preeditLen = lastSetComposingText?.length ?: 0
            val immediate = isImmediateCommitMode()

            recordImeCommit(outputText.trim())
            val expanded = macroExpanded
            // The engine is cleared first; the span is ended explicitly below so
            // a macro can still replace the text before finishing it.
            clearState(finishSpan = false)

            if (immediate) {
                if (preeditLen > 0) backspaceHandler.sendBackspaceEvents(ic, preeditLen)
                ic.commitText(outputText, 1)
                composingSpanActive = false
            } else if (expanded != null) {
                // Replace the whole composing region with the expansion in one
                // edit, then end the span. The word never blinks.
                if (composingSpanActive) {
                    ic.setComposingText(outputText, 1)
                    ic.finishComposingText()
                    composingSpanActive = false
                } else {
                    if (start >= 0 && preeditLen > 0) {
                        ic.setSelection(start + preeditLen, start + preeditLen)
                        ic.deleteSurroundingText(preeditLen, 0)
                    }
                    ic.commitText(outputText, 1)
                }
            } else {
                // The composing text already is the word: ending the span commits
                // it exactly once. Only the separator is new text.
                finishComposingSpan(ic)
                if (wordBreak.isNotEmpty()) ic.commitText(wordBreak, 1)
            }

            expectedEditorCaret = if (start >= 0) start + outputText.length else -1
            if (expanded != null) {
                lastExpandedMacro = MacroExpansionRecord(raw, outputText, System.currentTimeMillis())
            }
        } finally {
            if (!alreadyBatched) ic.endBatchEdit()
        }
        service.evaluateAutoShift()
    }

    /**
     * Inserts text that is not part of the Vietnamese buffer: an emoji, or a
     * run pasted from elsewhere.
     *
     * The composing range is settled first, through the same rule a letter
     * takes, so the editor is never handed a mutation the tracked state does
     * not describe. That is the whole point of the order here: the panel that
     * owns the key used to wrap this in its own batch and call commitText on a
     * cursor it had not closed, which left the range we track pointing into
     * the middle of what we had just written.
     */
    fun insertLiteral(text: String) {
        if (text.isEmpty()) return
        val ic = service.currentInputConnection ?: return
        lastExpandedMacro = null
        ic.beginBatchEdit()
        try {
            commitAndReset(alreadyBatched = true)
            ic.commitText(text, 1)
        } finally {
            ic.endBatchEdit()
        }
    }
}
