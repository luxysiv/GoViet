package com.goviet.keyboard.engine

import com.goviet.keyboard.VietnameseInputMethodService
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import java.lang.StringBuilder

private val CLEAN_LINE_REGEX = "[^a-zàáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ\\s]".toRegex()
private val SPLIT_WHITESPACE_REGEX = "\\s+".toRegex()

/**
 * ImeInputConnectionController (IME Input Controller)
 *
 * Architecture Role:
 * - Manages the IME layer interaction with Android's InputConnection.
 * - Handles composing text buffers (composingRaw), cursor tracking, selection, and backspace logic.
 * - Delegates Vietnamese syllable rules and settings (Telex, Simple Telex, Modern Style, Macros) to VietnameseInputEngine.
 */
class ImeInputConnectionController(
    val service: VietnameseInputMethodService,
    val inputEngine: VietnameseInputEngine
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
                variation == 224) { // 224 is TYPE_TEXT_VARIATION_WEB_PASSWORD
                typingMode = TypingMode.LATIN
                return
            }
        }
        typingMode = TypingMode.VIETNAMESE
    }

    val composingRaw = StringBuilder()
    var lastSetComposingText: String? = null
    var activeComposingShiftState = 0 // 0: lowercase, 1: title case, 2: uppercase (caps lock)
    var compositionOwnership: CompositionOwnership = CompositionOwnership.LIVE_VIETNAMESE
    private var lastShiftTime = 0L
    var isSelecting: Boolean = false
    var lastKeyPressTime = 0L
    var composingStartInEditor = -1
    var composingCursorIndex = 0

    // Transaction / Sequence ID tracking to prevent onUpdateSelection race conditions
    var currentTransactionId: Long = 0L
    var lastCommittedTransactionId: Long = 0L
    
    private val expectedCursorPositions = IntArray(16) { -1 }
    private var expectedCursorHead = 0

    fun pushExpectedCursor(cursor: Int) {
        if (cursor < 0) return
        expectedCursorPositions[expectedCursorHead] = cursor
        expectedCursorHead = (expectedCursorHead + 1) % expectedCursorPositions.size
    }

    fun isExpectedCursor(cursor: Int): Boolean {
        if (cursor < 0) return false
        for (i in expectedCursorPositions.indices) {
            if (expectedCursorPositions[i] == cursor) {
                expectedCursorPositions[i] = -1
                return true
            }
        }
        return false
    }

    fun clearExpectedCursors() {
        for (i in expectedCursorPositions.indices) {
            expectedCursorPositions[i] = -1
        }
        expectedCursorHead = 0
    }

    var expectedCursorStart: Int = -1
        set(value) {
            field = value
            if (value >= 0) pushExpectedCursor(value)
        }
    var expectedCursorEnd: Int = -1
        set(value) {
            field = value
            if (value >= 0) pushExpectedCursor(value)
        }

    // Cached cursor & selection state pushed by Android OS via onUpdateSelection
    var cachedSelStart: Int = 0
    var cachedSelEnd: Int = 0
    var cachedCandidatesStart: Int = -1
    var cachedCandidatesEnd: Int = -1

    var userMovedCursor: Boolean = false
    var userSelectedText: Boolean = false

    fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        cachedSelStart = newSelStart
        cachedSelEnd = newSelEnd
        cachedCandidatesStart = candidatesStart
        cachedCandidatesEnd = candidatesEnd

        // Resolve the composing region *before* deciding whether the cursor move is ours.
        // On the web (WebView/Chrome) the editor can emit spurious onUpdateSelection events
        // (reflow / autocomplete) whose cursor value coincides with a position we set earlier.
        // Consuming those blindly makes the IME treat a real user cursor move as its own and
        // jump the caret, so only swallow positions that actually fall inside the active
        // composing region for the current syllable.
        val lastDisplay = if (composingRaw.isEmpty()) null else (lastSetComposingText ?: compileComposingText())
        val compStart = if (candidatesStart >= 0) candidatesStart else composingStartInEditor
        val compEnd = if (compStart >= 0 && lastDisplay != null) compStart + lastDisplay.length else -1

        val insideComposingRegion = compStart >= 0 && newSelStart >= compStart &&
                newSelStart <= compEnd && newSelEnd == newSelStart

        // 1. If this update matches one of our recent expected cursor positions AND lies inside
        //    the current composing region, consume it as our own.
        val isExpected = insideComposingRegion && isExpectedCursor(newSelStart)
        if (isExpected) {
            userMovedCursor = false
            userSelectedText = false
            return
        }

        userMovedCursor = true
        userSelectedText = (newSelStart != newSelEnd)

        // 2. Synchronize composingStartInEditor if Android OS reports a valid composing region
        if (candidatesStart >= 0) {
            composingStartInEditor = candidatesStart
        }

        // 3. Active composing session handling
        if (composingRaw.isNotEmpty() && lastDisplay != null) {
            // If user moved cursor within the current composing syllable, update internal cursor index accurately
            if (compStart >= 0 && newSelStart == newSelEnd && newSelStart in compStart..compEnd) {
                val offsetInDisplay = (newSelStart - compStart).coerceIn(0, lastDisplay.length)
                composingCursorIndex = VietnameseCursorMapper.displayToRaw(
                    raw = composingRaw.toString(),
                    display = lastDisplay,
                    displayOffset = offsetInDisplay,
                    ownership = compositionOwnership,
                    options = inputEngine.options
                )
            } else {
                // A cursor move entirely outside the active composing region is treated as a
                // genuine user interaction: end composition so we do not silently relocate the caret.
                val ic = service.currentInputConnection
                if (ic != null) {
                    ic.beginBatchEdit()
                    try {
                        ic.finishComposingText()
                        clearState()
                    } finally {
                        ic.endBatchEdit()
                    }
                } else {
                    clearState()
                }
            }
        }
    }

    fun mapDisplayOffsetToRawCursor(raw: String, display: String, displayOffset: Int): Int {
        return VietnameseCursorMapper.displayToRaw(raw, display, displayOffset, compositionOwnership, inputEngine.options)
    }

    data class ImeCommitRecord(val word: String, val timestamp: Long)
    private var lastImeCommit: ImeCommitRecord? = null

    data class MacroExpansionRecord(val trigger: String, val expandedText: String, val timestamp: Long)
    var lastExpandedMacro: MacroExpansionRecord? = null

    class ImeSnapshot(
        var composingRaw: String = "",
        var composingCursorIndex: Int = 0,
        var displayCursorIndex: Int = 0,
        var shiftState: Int = 0,
        val composerSnapshot: VietnameseComposer.ComposerSnapshot = VietnameseComposer.ComposerSnapshot()
    ) {
        val displayText: String get() = composerSnapshot.displayText
        val ownership: CompositionOwnership get() = composerSnapshot.ownership

        fun set(
            raw: String,
            cursor: Int,
            shift: Int,
            snap: VietnameseComposer.ComposerSnapshot,
            displayCursor: Int = snap.displayText.length
        ) {
            composingRaw = raw
            composingCursorIndex = cursor
            displayCursorIndex = displayCursor
            shiftState = shift
            composerSnapshot.set(snap.state, snap.displayText, snap.ownership)
        }
    }

    private val imeSnapshotPool = Array(32) { ImeSnapshot() }
    var imeUndoCount = 0

    fun getTopImeSnapshot(): ImeSnapshot? = if (imeUndoCount > 0) imeSnapshotPool[imeUndoCount - 1] else null

    fun popImeSnapshot(): ImeSnapshot? {
        if (imeUndoCount > 0) {
            imeUndoCount--
            return imeSnapshotPool[imeUndoCount]
        }
        return null
    }

    fun pushImeSnapshot(
        raw: String,
        cursor: Int,
        shift: Int,
        snap: VietnameseComposer.ComposerSnapshot,
        displayCursor: Int = snap.displayText.length
    ) {
        if (imeUndoCount < imeSnapshotPool.size) {
            imeSnapshotPool[imeUndoCount].set(raw, cursor, shift, snap, displayCursor)
            imeUndoCount++
        }
    }

    var lastCommittedChar: Char? = null
    var lastCommittedSeparator: String? = null

    private fun recordImeCommit(word: String) {
        val trimmed = word.trim()
        if (trimmed.isNotEmpty()) {
            lastCommittedChar = trimmed.lastOrNull()
            lastImeCommit = ImeCommitRecord(
                word = VietnameseCharUtils.normalizeNfc(trimmed),
                timestamp = System.currentTimeMillis()
            )
            service.lastCommittedWord = trimmed
        }
    }

    fun clearState() {
        composingRaw.clear()
        lastSetComposingText = null
        activeComposingShiftState = 0
        compositionOwnership = CompositionOwnership.LIVE_VIETNAMESE
        lastKeyPressTime = 0L
        composingStartInEditor = -1
        composingCursorIndex = 0
        expectedCursorStart = -1
        expectedCursorEnd = -1
        clearExpectedCursors()
        imeUndoCount = 0
        lastExpandedMacro = null
        lastCommittedSeparator = null
        inputEngine.reset()
    }

    fun isVietnameseLetterChar(c: Char): Boolean {
        if (c.isDigit()) return false
        if (c.isLetter()) return true
        val type = Character.getType(c)
        return type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
    }

    fun isPotentialTelexModifier(key: String): Boolean {
        if (key.length != 1) return false
        val c = key[0].lowercaseChar()
        return c in listOf('s', 'f', 'r', 'x', 'j', 'w', 'a', 'e', 'o', 'd', '[', ']', '{', '}')
    }

    fun findWordAroundCursor(ic: InputConnection): WordAtCursor? {
        val beforeText = ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
        val afterText = ic.getTextAfterCursor(64, 0)?.toString() ?: ""

        var i = beforeText.length - 1
        while (i >= 0 && isVietnameseLetterChar(beforeText[i])) {
            i--
        }
        val wordBefore = beforeText.substring(i + 1)

        var j = 0
        while (j < afterText.length && isVietnameseLetterChar(afterText[j])) {
            j++
        }
        val wordAfter = afterText.substring(0, j)

        val fullWord = wordBefore + wordAfter
        if (fullWord.isEmpty()) return null

        val fresh = getCachedCursorPosition(ic)
        val curSelStart = fresh?.first ?: cachedSelStart

        return WordAtCursor(
            text = fullWord,
            startInEditor = if (curSelStart >= wordBefore.length) curSelStart - wordBefore.length else 0,
            endInEditor = curSelStart + wordAfter.length,
            cursorOffset = wordBefore.length
        )
    }

    fun adoptWordAtCursor(ic: InputConnection): Boolean {
        if (service._languageMode.value != "VIE") {
            return false
        }
        if (isBypassVietnameseComposing()) {
            return false
        }

        val wordAtCursor = findWordAroundCursor(ic) ?: return false
        val fullWordRaw = wordAtCursor.text
        val cursorOffset = wordAtCursor.cursorOffset
        val wordBeforeRaw = fullWordRaw.substring(0, cursorOffset)
        val wordAfterRaw = fullWordRaw.substring(cursorOffset)

        val fullWordNfc = VietnameseCharUtils.normalizeNfc(fullWordRaw)
        if (!fullWordNfc.all { isVietnameseLetterChar(it) }) {
            return false
        }

        val ownership = EditedVietnameseRecognizer.classify(fullWordNfc, inputEngine.options)

        if (ownership == CompositionOwnership.ADOPTED_VIETNAMESE) {
            val analysis = VietnameseLexicalParser.analyze(fullWordNfc, inputEngine.options)
            if (analysis.isValid) {
                val (canonicalRaw, snapshots) = VietnameseSnapshotBuilder.generate(analysis, inputEngine.options)
                if (canonicalRaw.isEmpty()) {
                    return false
                }

                val finalCanonical = analysis.canonicalRaw
                composingRaw.clear()
                composingRaw.append(finalCanonical)
                compositionOwnership = CompositionOwnership.ADOPTED_VIETNAMESE

                inputEngine.loadAdoptedSyllable(analysis.syllableState, finalCanonical, snapshots)

                composingCursorIndex = VietnameseCursorMapper.displayToRaw(
                    raw = finalCanonical,
                    display = fullWordNfc,
                    displayOffset = cursorOffset,
                    ownership = CompositionOwnership.ADOPTED_VIETNAMESE,
                    options = inputEngine.options
                )

                composingStartInEditor = wordAtCursor.startInEditor

                imeUndoCount = 0
                val runningRaw = StringBuilder()
                if (snapshots.isNotEmpty()) {
                    for (snapIdx in snapshots.indices) {
                        val snap = snapshots[snapIdx]
                        if (snapIdx < finalCanonical.length) {
                            runningRaw.append(finalCanonical[snapIdx])
                        }
                        pushImeSnapshot(
                            raw = runningRaw.toString(),
                            cursor = runningRaw.length,
                            shift = activeComposingShiftState,
                            snap = snap
                        )
                    }
                } else {
                    val singleSnap = VietnameseComposer.ComposerSnapshot(
                        inputEngine.getCurrentSyllable(),
                        fullWordNfc,
                        CompositionOwnership.ADOPTED_VIETNAMESE
                    )
                    pushImeSnapshot(
                        raw = finalCanonical,
                        cursor = composingCursorIndex,
                        shift = activeComposingShiftState,
                        snap = singleSnap
                    )
                }

                if (isImmediateCommitMode()) {
                    ic.deleteSurroundingText(wordBeforeRaw.length, wordAfterRaw.length)
                    lastSetComposingText = ""
                } else {
                    val regionStart = composingStartInEditor
                    val regionEnd = regionStart + wordBeforeRaw.length + wordAfterRaw.length
                    if (regionStart >= 0) {
                        ic.setComposingRegion(regionStart, regionEnd)
                    }
                    lastSetComposingText = fullWordNfc
                }
                userMovedCursor = false
                return true
            }
        }

        // Adopt as EDITED_LITERAL: preserve literal characters without Telex transformation
        composingRaw.clear()
        composingRaw.append(fullWordNfc)
        compositionOwnership = CompositionOwnership.EDITED_LITERAL
        composingCursorIndex = cursorOffset.coerceIn(0, fullWordNfc.length)

        inputEngine.loadLiteral(fullWordNfc)

        composingStartInEditor = wordAtCursor.startInEditor

        imeUndoCount = 0
        val snap = VietnameseComposer.ComposerSnapshot(
            inputEngine.getCurrentSyllable(),
            fullWordNfc,
            CompositionOwnership.EDITED_LITERAL
        )
        pushImeSnapshot(
            raw = fullWordNfc,
            cursor = composingCursorIndex,
            shift = activeComposingShiftState,
            snap = snap
        )

        if (isImmediateCommitMode()) {
            ic.deleteSurroundingText(wordBeforeRaw.length, wordAfterRaw.length)
            lastSetComposingText = ""
        } else {
            val regionStart = composingStartInEditor
            val regionEnd = regionStart + wordBeforeRaw.length + wordAfterRaw.length
            if (regionStart >= 0) {
                ic.setComposingRegion(regionStart, regionEnd)
            }
            lastSetComposingText = fullWordNfc
        }
        userMovedCursor = false
        return true
    }

    fun isImmediateCommitMode(): Boolean {
        val editorInfo = service.currentInputEditorInfo ?: return false
        return editorInfo.inputType == android.text.InputType.TYPE_NULL
    }

    private fun isBypassVietnameseComposing(): Boolean {
        return typingMode == TypingMode.LATIN
    }

    private fun getCachedCursorPosition(ic: InputConnection): Pair<Int, Int> {
        return Pair(cachedSelStart, cachedSelEnd)
    }

    fun isComposingStateDesynced(ic: InputConnection): Boolean {
        if (composingRaw.isEmpty()) return false
        val start = composingStartInEditor
        if (start < 0) return true
        val expectedLen = lastSetComposingText?.length ?: 0
        val expectedEnd = start + expectedLen

        if (cachedCandidatesStart >= 0) {
            if (cachedCandidatesStart != start || (cachedCandidatesEnd >= 0 && cachedCandidatesEnd != expectedEnd)) {
                return true
            }
        }
        return false
    }

    private fun isVietnameseComposingKey(key: String): Boolean {
        if (service._languageMode.value == "ENG") return false
        if (isBypassVietnameseComposing()) return false
        if (key.length != 1) return false
        val char = key[0]
        if (char in 'a'..'z' || char in 'A'..'Z' || char.lowercaseChar() != char.uppercaseChar()) return true
        
        // In Telex, brackets [ and ] and shifted variants { and } are shortcut inputs for ư and ơ
        if (char == '[' || char == ']' || char == '{' || char == '}') {
            return true
        }
        
        return false
    }

    /* =========================================================================
     * COMPOSING BUFFER & UI LIFECYCLE
     * ========================================================================= */

    fun resetComposingUI(ic: InputConnection, backspaceCountIfImmediate: Int = 0) {
        ic.beginBatchEdit()
        try {
            clearExpectedCursors()
            composingRaw.clear()
            activeComposingShiftState = 0
            lastSetComposingText = null
            expectedCursorStart = -1
            expectedCursorEnd = -1
            imeUndoCount = 0
            inputEngine.reset()
            currentTransactionId++
            if (isImmediateCommitMode()) {
                if (backspaceCountIfImmediate > 0) {
                    backspaceHandler.sendBackspaceEvents(ic, backspaceCountIfImmediate)
                }
            } else {
                ic.setComposingText("", 1)
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    fun updateComposingUI(ic: InputConnection, lastLenIfImmediate: Int = 0, explicitCompiled: String? = null) {
        ic.beginBatchEdit()
        try {
            clearExpectedCursors()
            val compiled = explicitCompiled ?: compileComposingText()
            val compiledPrefixLen = VietnameseCursorMapper.rawToDisplay(
                raw = composingRaw.toString(),
                rawCursor = composingCursorIndex,
                ownership = compositionOwnership,
                options = inputEngine.options
            )
            currentTransactionId++
            if (isImmediateCommitMode()) {
                val lastStr = lastSetComposingText ?: ""
                if (lastStr.isNotEmpty() && compiled == lastStr.substring(0, lastStr.length - 1)) {
                    backspaceHandler.sendBackspaceEvents(ic, 1)
                } else if (lastLenIfImmediate > 0) {
                    backspaceHandler.sendBackspaceEvents(ic, lastLenIfImmediate)
                }
                ic.commitText(compiled, 1)
            } else {
                ic.setComposingText(compiled, 1)
            }
            if (composingCursorIndex != composingRaw.length && composingStartInEditor >= 0) {
                val newCursor = composingStartInEditor + compiledPrefixLen
                if (newCursor >= 0) {
                    ic.setSelection(newCursor, newCursor)
                    expectedCursorStart = newCursor
                    expectedCursorEnd = newCursor
                }
            } else if (composingStartInEditor >= 0) {
                expectedCursorStart = composingStartInEditor + compiled.length
                expectedCursorEnd = expectedCursorStart
            }
            lastSetComposingText = compiled
        } finally {
            ic.endBatchEdit()
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

    fun getPrecedingWords(cachedBefore: CharSequence?): List<String> {
        val before = cachedBefore ?: return emptyList()
        val cleaned = CLEAN_LINE_REGEX.replace(before.toString().trim().lowercase(), "")
        return cleaned.split(SPLIT_WHITESPACE_REGEX).filter { it.isNotEmpty() }
    }

    fun getPrecedingWords(ic: InputConnection): List<String> {
        return getPrecedingWords(ic.getTextBeforeCursor(120, 0))
    }

    private fun handleSeparator(ic: InputConnection, separator: String) {
        if (composingRaw.isNotEmpty()) {
            commitAndReset(wordBreak = separator)
        } else {
            commitAndReset()
            ic.commitText(separator, 1)
        }
        lastCommittedChar = separator.lastOrNull()
        lastCommittedSeparator = separator
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
                commitAndReset()
                lastCommittedChar = '\n'
                lastCommittedSeparator = "\n"
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
            } else if (BoundaryClassifier.isBoundary(key, isTelexMode)) {
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
                        if (otp.isNotEmpty()) {
                            ic.commitText(otp, 1)
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
                    if (!isVietnameseComposingKey(key)) {
                        commitAndReset()
                        ic.commitText(actualKey, 1)
                        service.lastCommittedWord = actualKey
                        lastCommittedChar = actualKey.lastOrNull()
                        lastCommittedSeparator = null
                        service.shiftController.consumeSingleShift()
                        service.evaluateAutoShift(forceIpc = false)
                    } else {
                        if (composingRaw.isNotEmpty() && isComposingStateDesynced(ic)) {
                            ic.finishComposingText()
                            clearState()
                        }
                        if (composingRaw.isEmpty()) {
                            val isModifier = isPotentialTelexModifier(actualKey)
                            var adopted = false
                            val wordAtCursor = findWordAroundCursor(ic)
                            if (wordAtCursor != null) {
                                val isMiddleOfWord = wordAtCursor.cursorOffset < wordAtCursor.text.length
                                if ((isModifier && wordAtCursor.text.isNotEmpty()) || (userMovedCursor && isMiddleOfWord)) {
                                    adopted = adoptWordAtCursor(ic)
                                }
                            }
                            if (!adopted) {
                                val currentSel = cachedSelStart
                                composingStartInEditor = if (currentSel >= 0) currentSel else -1
                                composingCursorIndex = 0
                                compositionOwnership = CompositionOwnership.LIVE_VIETNAMESE
                            }
                            userMovedCursor = false
                        }
                        if (composingRaw.isEmpty()) {
                            activeComposingShiftState = service.shiftController.value
                        }
                        val lastLen = lastSetComposingText?.length ?: 0
                        composingRaw.insert(composingCursorIndex, actualKey)
                        composingCursorIndex += actualKey.length
                        lastCommittedChar = actualKey.lastOrNull()
                        lastCommittedSeparator = null

                        val (compiledText, snap) = inputEngine.syncWithRaw(composingRaw.toString(), compositionOwnership)
                        val casedDisplay = VietnameseCharUtils.applyCasingFromRaw(compiledText, composingRaw.toString())

                        updateComposingUI(ic, lastLen, casedDisplay)

                        val currentCompiled = lastSetComposingText ?: casedDisplay
                        snap.displayText = currentCompiled
                        pushImeSnapshot(
                            raw = composingRaw.toString(),
                            cursor = composingCursorIndex,
                            shift = activeComposingShiftState,
                            snap = snap
                        )

                        if (isImmediateCommitMode()) {
                            recordImeCommit(currentCompiled)
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
                val selected = ic.getSelectedText(0)
                if (selected != null && selected.isNotEmpty()) {
                    ic.commitText("", 1)
                } else {
                    if (isImmediateCommitMode()) {
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL))
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_FORWARD_DEL))
                    } else {
                        backspaceHandler.deleteNextGraphemeOrChar(ic)
                    }
                }
            }
        }
    }

    fun compileComposingText(): String {
        if (compositionOwnership == CompositionOwnership.EDITED_LITERAL) {
            return composingRaw.toString()
        }
        return compileText(composingRaw.toString())
    }

    fun compileText(raw: String): String {
        val compiled = inputEngine.process(raw)
        return VietnameseCharUtils.applyCasingFromRaw(compiled, raw)
    }

    private fun tryExpandMacro(raw: String, wordBreak: String): String? {
        if (!inputEngine.macroEnabled) return null
        val store = inputEngine.macroStore ?: return null
        if (store.isEmpty()) return null

        // Case 1: unaccented / raw trigger (e.g. "vn" -> "Việt Nam", "rs" -> "RoSino18k")
        store.lookup(raw.lowercase())?.let { expansion ->
            return applyMacroCase(expansion, raw) + wordBreak
        }

        // Case 2: accented Vietnamese trigger (e.g. "đc" -> "được", "ng" -> "người")
        val composed = compileText(raw)   // without wordBreak
        if (composed != raw) {
            store.lookup(composed.lowercase())?.let { expansion ->
                return applyMacroCase(expansion, composed) + wordBreak
            }
        }
        return null
    }

    private fun applyMacroCase(expansion: String, typed: String): String =
        if (typed.isNotEmpty() && typed.all { it.isUpperCase() }) expansion.uppercase() else expansion

    fun commitAndReset(wordBreak: String = "") {
        if (composingRaw.isNotEmpty()) {
            val ic = service.currentInputConnection
            if (ic != null) {
                ic.beginBatchEdit()
                try {
                    clearExpectedCursors()
                    val raw = composingRaw.toString()
                    val macroExpanded = tryExpandMacro(raw, wordBreak)
                    val outputText = macroExpanded ?: (if (compositionOwnership == CompositionOwnership.EDITED_LITERAL) raw + wordBreak else compileText(raw) + wordBreak)
                    currentTransactionId++

                    if (isImmediateCommitMode()) {
                        val lastLen = lastSetComposingText?.length ?: 0
                        if (lastLen > 0) {
                            backspaceHandler.sendBackspaceEvents(ic, lastLen)
                        }
                        ic.commitText(outputText, 1)
                    } else {
                        if (lastSetComposingText == outputText && wordBreak.isEmpty()) {
                            ic.finishComposingText()
                        } else {
                            ic.commitText(outputText, 1)
                        }
                    }
                    recordImeCommit(outputText.trim())
                    clearState()
                    if (macroExpanded != null) {
                        lastExpandedMacro = MacroExpansionRecord(raw, outputText, System.currentTimeMillis())
                    }
                } finally {
                    ic.endBatchEdit()
                }
            } else {
                clearState()
            }
            service.evaluateAutoShift()
        }
    }

    fun commitAndFinishing(wordBreak: String = "") = commitAndReset(wordBreak)
}
