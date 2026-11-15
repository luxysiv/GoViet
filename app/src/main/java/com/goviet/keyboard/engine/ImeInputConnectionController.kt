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

    /**
     * How an editor accepts the removal half of a preedit rewrite.
     *
     * Decided once per field, and only ever from the editor's own text — never
     * from the return value of a write. replaceText reports success even when
     * the editor silently ignored it (Chromium's WebView does exactly this), so
     * "the write landed" is a fact to be verified, not a boolean to be trusted.
     */
    enum class DeliveryMode {
        /** Not decided yet: the first rewrite that has to remove something decides. */
        UNKNOWN,

        /** The editor keeps the preedit between writes; deleteSurroundingText reaches it. */
        RETAINS,

        /**
         * The editor forwards the preedit and drops it on every write — a web
         * terminal whose hidden field is cleared after each keystroke. Deletions
         * there have to travel as key events, not text commands.
         */
        TRANSIENT,
    }

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

    /** Which removal mechanism this field honours; see [DeliveryMode]. */
    var deliveryMode: DeliveryMode = DeliveryMode.UNKNOWN
        private set

    /**
     * A new field may be a different kind of editor, so the previous verdict is
     * forgotten. Called when the input view starts for a field, not on every
     * state reset: the verdict survives a commit/space because the field does.
     */
    fun resetDeliveryMode() {
        deliveryMode = DeliveryMode.UNKNOWN
    }

    /**
     * True when deletions must travel as key events rather than text commands.
     *
     * TYPE_NULL terminals always needed this. Transient web fields (a browser
     * terminal whose hidden textarea is cleared after every keystroke) need it
     * too, and they are NOT TYPE_NULL — which is exactly why the old code
     * duplicated characters there: its text-command deletes never reached the
     * app, so each write re-emitted the whole word.
     */
    fun usesKeyEventDeletes(): Boolean =
        isImmediateCommitMode() || deliveryMode == DeliveryMode.TRANSIENT

    /**
     * "Is this onUpdateSelection ours?" is answered by GEOMETRY, not by a ledger of
     * what we predicted and not by a clock. There is deliberately no TTL, no ring
     * buffer, no re-announce throttle and no "recent typing" window here — the rule
     * itself lives in [reconcileSelection], which sees only these fields:
     *
     *  - [composingStartInEditor] + [lastSetComposingText] delimit the preedit we
     *    own, and every write is one replaceText over exactly that range, so the
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
        val verdict = reconcileSelection(
            newSelStart = newSelStart,
            newSelEnd = newSelEnd,
            composing = inputEngine.isComposing(),
            preeditStart = composingStartInEditor,
            preeditLength = lastSetComposingText?.length ?: 0
        )

        when (verdict) {
            // The editor said nothing, so there is nothing to move.
            SelectionVerdict.NO_INFO -> return

            // Echo of our own write, or a tap inside our own preedit: keep the
            // composition and adopt the caret, instead of dropping the buffer and
            // rewriting the word from scratch (the old Zalo flicker).
            SelectionVerdict.KEEP_PREEDIT -> {
                // A transient field has no stable caret to adopt: its hidden text
                // is drained and cleared after every keystroke, so the position the
                // editor reports describes text we never wrote. Adopting it would
                // move the engine cursor into the wrong place and re-order the next
                // keystroke. The engine cursor is the only one that means anything
                // there, so it is left alone.
                if (deliveryMode != DeliveryMode.TRANSIENT) adoptCaretIntoPreedit(newSelStart)
                userMovedCursor = false
                userSelectedText = false
                return
            }

            SelectionVerdict.USER_MOVE, SelectionVerdict.USER_SELECTION -> {
                userMovedCursor = true
                userSelectedText = verdict == SelectionVerdict.USER_SELECTION
                // A transient field reports the caret of an emptied editor, so an
                // apparent move is not evidence the user left the word; dropping
                // the buffer on it would lose a half-typed word. A genuine
                // selection is still honoured, because selecting text is the one
                // move a terminal forwards as such.
                if (inputEngine.isComposing() && (deliveryMode != DeliveryMode.TRANSIENT || verdict == SelectionVerdict.USER_SELECTION)) {
                    // No finishComposingText(): the preedit is written with
                    // replaceText, which never creates a composing span, so there
                    // is nothing to finish. The tracked range dies with the buffer.
                    clearState()
                }
            }
        }

        // A tap inside committed text adopts the Vietnamese prefix before the
        // caret right away. No typing-window gate: a callback that is genuinely
        // ours never reaches here (the two branches above absorb it), so any
        // callback arriving here is a real editor-side move and must be acted on
        // immediately — otherwise the next keystroke would land at the old caret.
        service.currentInputConnection?.let { adoptPrefixAtCaret(it) }
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
        // The editor caret is already exactly where the preedit ends, and no
        // composing region is announced (the preedit carries no span, so no
        // underline can appear over freshly adopted words), so adoption needs
        // no editor call at all.
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

    fun clearState() {
        inputEngine.reset()
        lastSetComposingText = null
        lastKeyPressTime = 0L
        composingStartInEditor = -1
        composingCursorIndex = 0
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

    fun findWordAroundCursor(ic: InputConnection): WordAtCursor? {
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

        // Absolute offsets are only meaningful relative to the caret, and the
        // caret is the editor's to tell us: without it there is no word to
        // describe, and guessing position 0 would hand back a range pointing at
        // the start of the document.
        val curSelStart = realSelectionStart(ic)
        if (curSelStart < 0) return null

        return WordAtCursor(
            text = fullWord,
            startInEditor = (curSelStart - wordBefore.length).coerceAtLeast(0),
            endInEditor = curSelStart + wordAfter.length,
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
    internal fun applyKeyToComposingBuffer(key: Char): Boolean {
        if (inputEngine.isComposing() && composingCursorIndex <= 0 && inputEngine.composingRawLength() > 0) {
            inputEngine.reset()
            inputEngine.composeAsVietnamese = true
            composingCursorIndex = 0
            lastSetComposingText = null
            inputEngine.insertComposingKey(0, key)
            composingCursorIndex = 1
            return true
        }
        inputEngine.insertComposingKey(composingCursorIndex, key)
        composingCursorIndex += 1
        return false
    }

    /**
     * Dedicated cursor→engine bridge: decides how a fresh keystroke starts a composition
     * session relative to existing committed text at the editor caret.
     */
    private fun resolveCompositionAtCursor(
        ic: InputConnection,
        key: String,
        wordAtCursor: WordAtCursor?
    ) {
        // -1 when the editor will not say: no managed range is claimed, so the
        // preedit is written as a plain insertion at the editor's own caret.
        composingStartInEditor = realSelectionStart(ic)
        composingCursorIndex = 0

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
            userMovedCursor = false
            return
        }
        if (wordAtCursor != null && isAtEnd && wordText.isNotEmpty() && !composeAsVietnameseOnsetSeed && regionValid) {
            composingStartInEditor = wordAtCursor.startInEditor
            inputEngine.composeAsVietnamese = false
            inputEngine.setComposingRaw(wordText)
            composingCursorIndex = wordText.length
            lastSetComposingText = wordText
            userMovedCursor = false
            return
        }

        inputEngine.composeAsVietnamese = !(wordAtCursor != null && wordCursorOffset > 0 && wordCursorOffset < wordTextLength)

        userMovedCursor = false
    }

    /**
     * The one deliberate exception to "every edit is a replaceText": TYPE_NULL
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
        } else if (deliveryMode == DeliveryMode.TRANSIENT) {
            // The word was forwarded to the app as it was typed, so it leaves
            // through the app's own delete key rather than a text command.
            if (lastStr.isNotEmpty()) backspaceHandler.sendBackspaceEvents(ic, lastStr.length)
        } else if (lastStr.isNotEmpty()) {
            // Both callers — delete-word and backspace-to-empty — mean "drop the
            // whole preedit". The range is known exactly, so the preedit leaves
            // in ONE atomic op: no caret parking, no delete-at-caret guesswork.
            if (start < 0 || !ic.replaceText(start, start + lastStr.length, "", 1, null)) {
                if (start >= 0) ic.setSelection(start + lastStr.length, start + lastStr.length)
                ic.deleteSurroundingText(lastStr.length, 0)
            }
        }
    }

    /**
     * Writes the preedit to the editor as the minimal edit against what was
     * already delivered, in the one order that is correct on every editor.
     *
     * The common case — the new display is the old one plus a tail, caret at the
     * end — commits ONLY the tail. That is the single write that is correct
     * everywhere: a normal editor appends at the caret, while a transient field
     * (a web terminal whose hidden textarea was already forwarded and cleared)
     * has nothing left to replace, so re-committing the whole word would deliver
     * it twice — the "nhân chữ" duplication. A tail is always new text, so it
     * lands exactly once in both.
     *
     * Only when something inside the preedit changed (a tone mark, a vowel fold,
     * a backspace, a caret moved into the word) does a removal become necessary.
     * [diffRewrite] then deletes just the changed suffix through the mechanism
     * the editor actually honours, chosen once by [DeliveryMode]. replaceText is
     * tried first because it is atomic and leaves no frame in which the word is
     * missing (the old Zalo/Telegram flicker), but its success is verified
     * against the editor's text, not trusted: it returns true even when ignored.
     *
     * [lastSetComposingText] stays the single owner of the preedit bookkeeping:
     * it holds what the editor was given, is read here as the previous delivery,
     * and is replaced before the editor is touched, so the echo reconciliation
     * in [onUpdateSelection] stays provable.
     */
    fun writePreedit(ic: InputConnection, display: String) {
        val previous = lastSetComposingText ?: ""
        val start = composingStartInEditor
        val caretInDisplay = displayCursorIndex()
        lastSetComposingText = display

        // Fast path: a pure extension at the end of the preedit needs no removal.
        if (caretInDisplay == display.length &&
            display.length >= previous.length &&
            display.startsWith(previous)) {
            val tail = display.substring(previous.length)
            if (tail.isNotEmpty()) ic.commitText(tail, 1)
            return
        }

        if (deliveryMode != DeliveryMode.TRANSIENT &&
            start >= 0 &&
            ic.replaceText(start, start + previous.length, display, 1, null)) {
            // Already proven to land here, or proven right now on first use.
            if (deliveryMode == DeliveryMode.RETAINS || verifyDelivery(ic, display)) {
                deliveryMode = DeliveryMode.RETAINS
                if (caretInDisplay != display.length) {
                    // newCursorPosition = 1 always lands the caret after the
                    // whole new text, so a preedit caret in the middle needs one
                    // extra selection-only call: it changes no text, hence cannot
                    // make the editor redraw the word.
                    ic.setSelection(start + caretInDisplay, start + caretInDisplay)
                }
                return
            }
            // replaceText reported success but the editor ignored it (WebView).
            // The old preedit is still in front of the caret, so the diff below
            // is measured from the right place — only delivered the other way.
            deliveryMode = DeliveryMode.TRANSIENT
        }

        diffRewrite(ic, display, previous, start, caretInDisplay)
    }

    /**
     * Deletes only the changed suffix of the delivered preedit and commits the
     * new tail. Used for transient fields and for editors that do not implement
     * replaceText (Flutter's TextInputConnection and other hand-rolled
     * connections); the removal mechanism is chosen by [deletePreeditSuffix].
     */
    private fun diffRewrite(
        ic: InputConnection,
        display: String,
        previous: String,
        start: Int,
        caretInDisplay: Int
    ) {
        val prefix = commonPrefixLength(previous, display)
        val deleteCount = previous.length - prefix
        if (deleteCount > 0) deletePreeditSuffix(ic, previous, deleteCount)
        val insert = display.substring(prefix)
        if (insert.isNotEmpty()) ic.commitText(insert, 1)
        if (start >= 0 && caretInDisplay != display.length) {
            ic.setSelection(start + caretInDisplay, start + caretInDisplay)
        }
    }

    private fun deletePreeditSuffix(ic: InputConnection, previous: String, count: Int) {
        when (deliveryMode) {
            DeliveryMode.TRANSIENT -> backspaceHandler.sendBackspaceEvents(ic, count)
            DeliveryMode.RETAINS -> ic.deleteSurroundingText(count, 0)
            DeliveryMode.UNKNOWN -> {
                // Decide once, by asking the editor whether the text we already
                // delivered is really in front of the caret. A stale read here
                // only means the field is treated as transient, which is safe:
                // key-event deletes reach both kinds of editor.
                if (verifyDelivery(ic, previous)) {
                    deliveryMode = DeliveryMode.RETAINS
                    ic.deleteSurroundingText(count, 0)
                } else {
                    deliveryMode = DeliveryMode.TRANSIENT
                    backspaceHandler.sendBackspaceEvents(ic, count)
                }
            }
        }
    }

    /**
     * Asks the editor whether [expected] is the text right before the caret.
     * This is the only trustworthy answer to "did the write land": both
     * replaceText and deleteSurroundingText report success even when the editor
     * ignored them, so a false here is treated as "this field is transient",
     * which routes later removals through key events.
     */
    private fun verifyDelivery(ic: InputConnection, expected: String): Boolean {
        if (expected.isEmpty()) return true
        val before = ic.getTextBeforeCursor(expected.length, 0)?.toString() ?: return false
        return before.endsWith(expected)
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

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
                            val wordAtCursor = findWordAroundCursor(ic)
                            resolveCompositionAtCursor(ic, actualKey, wordAtCursor)
                        }
                        val lastLen = lastSetComposingText?.length ?: 0
                        applyKeyToComposingBuffer(actualKey[0])

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
        for (i in 0..raw.length) {
            inputEngine.compileRawInto(raw, inputEngine.composeAsVietnamese, displayBuf, i)
            if (displayPrefixMatches(displayBuf, display, displayOffset)) return i
        }
        return displayOffset.coerceAtMost(raw.length)
    }

    private fun displayPrefixMatches(buf: OwnedBuffer, display: String, len: Int): Boolean {
        if (buf.len != len) return false
        for (j in 0 until len) {
            if (buf[j] != display[j]) return false
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
        if (inputEngine.isComposing()) {
            val ic = service.currentInputConnection
            if (ic != null) {
                if (!alreadyBatched) ic.beginBatchEdit()
                try {
                    val raw = inputEngine.composingRaw().toString()
                    val macroExpanded = tryExpandMacro(raw, wordBreak)
                    val outputText = macroExpanded ?: (if (!inputEngine.composeAsVietnamese) raw + wordBreak else compileRawDisplay() + wordBreak)
                    val start = composingStartInEditor
                    val preeditLen = lastSetComposingText?.length ?: 0
                    val immediate = isImmediateCommitMode()
                    // Local state first: the engine and the tracked range describe
                    // exactly what the editor is about to be given before a single
                    // editor call is made.
                    recordImeCommit(outputText.trim())
                    val expanded = macroExpanded
                    clearState()

                    if (immediate) {
                        if (preeditLen > 0) {
                            backspaceHandler.sendBackspaceEvents(ic, preeditLen)
                        }
                        ic.commitText(outputText, 1)
                    } else if (deliveryMode == DeliveryMode.TRANSIENT) {
                        // The word reached the app as it was typed, so on a plain
                        // commit only the separator is new. A macro has to retract
                        // the delivered word through the app's own delete key and
                        // type the expansion in its place.
                        if (macroExpanded != null) {
                            if (preeditLen > 0) {
                                backspaceHandler.sendBackspaceEvents(ic, preeditLen)
                            }
                            ic.commitText(outputText, 1)
                        } else if (wordBreak.isNotEmpty()) {
                            ic.commitText(wordBreak, 1)
                        }
                    } else if (macroExpanded != null) {
                        // Macro expansion replaces the whole preedit range in one
                        // atomic op, so the word never blinks between the old and
                        // the expanded text. The write is verified the same way a
                        // keystroke's is: replaceText reports success even when the
                        // editor ignored it, so a field that never needed a rewrite
                        // until now (a terminal typed in plain letters) is detected
                        // here instead of silently keeping the unexpanded word.
                        val replaced = start >= 0 &&
                            ic.replaceText(start, start + preeditLen, outputText, 1, null)
                        if (replaced && (deliveryMode == DeliveryMode.RETAINS || verifyDelivery(ic, outputText))) {
                            deliveryMode = DeliveryMode.RETAINS
                        } else {
                            deliveryMode = DeliveryMode.TRANSIENT
                            if (preeditLen > 0) {
                                backspaceHandler.sendBackspaceEvents(ic, preeditLen)
                            }
                            ic.commitText(outputText, 1)
                        }
                    } else if (wordBreak.isNotEmpty()) {
                        // The preedit text is already committed; only the separator
                        // is new. No finishComposingText: the preedit carries no
                        // composing span, so there is nothing to finish.
                        ic.commitText(wordBreak, 1)
                    }
                    // Pure commit: the text stays exactly as it is, so there is
                    // nothing left to send to the editor.
                    if (expanded != null) {
                        lastExpandedMacro = MacroExpansionRecord(raw, outputText, System.currentTimeMillis())
                    }
                } finally {
                    if (!alreadyBatched) ic.endBatchEdit()
                }
            } else {
                clearState()
            }
            service.evaluateAutoShift()
        }
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
