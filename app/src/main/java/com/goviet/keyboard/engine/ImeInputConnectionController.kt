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
 * - Delegates Vietnamese syllable rules and settings (Telex, VNI, Simple Telex, Modern Style, Macros) to GoVietInputEngine.
 */
class ImeInputConnectionController(
    private val service: VietnameseInputMethodService,
    private val inputEngine: GoVietInputEngine
) {

    private val TAG = "ImeInputConnectionController"
    
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
    private var lastShiftTime = 0L
    var isSelecting: Boolean = false
    var lastKeyPressTime = 0L
    var composingStartInEditor = -1
    var composingCursorIndex = 0

    data class ImeCommitRecord(val word: String, val timestamp: Long)
    private var lastImeCommit: ImeCommitRecord? = null

    private fun recordImeCommit(word: String) {
        val trimmed = word.trim()
        if (trimmed.isNotEmpty()) {
            lastImeCommit = ImeCommitRecord(
                word = java.text.Normalizer.normalize(trimmed, java.text.Normalizer.Form.NFC),
                timestamp = System.currentTimeMillis()
            )
            service.lastCommittedWord = trimmed
        }
    }

    fun clearState() {
        composingRaw.clear()
        lastSetComposingText = null
        activeComposingShiftState = 0
        lastKeyPressTime = 0L
        composingStartInEditor = -1
        composingCursorIndex = 0
        inputEngine.reset()
    }

    private fun isVietnameseWordChar(c: Char): Boolean {
        if (c.isLetter() || c.isDigit()) return true
        val type = Character.getType(c)
        return type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
    }

    private fun decomposeWord(word: String, isVni: Boolean): String {
        val sb = java.lang.StringBuilder()
        for (i in 0 until word.length) {
            sb.append(VietnameseCharDecomposer.decomposeChar(word[i], isVni))
        }
        return sb.toString()
    }

    private fun adoptWordAtCursor(ic: InputConnection): Boolean {
        if (service._languageMode.value != "VIE") {
            return false
        }
        if (isBypassVietnameseComposing()) {
            return false
        }
        val beforeText = ic.getTextBeforeCursor(100, 0) ?: ""
        val afterText = ic.getTextAfterCursor(100, 0) ?: ""
        
        val before = beforeText.toString()
        val after = afterText.toString()

        val hasLetterBefore = before.isNotEmpty() && isVietnameseWordChar(before.last())
        val hasLetterAfter = after.isNotEmpty() && isVietnameseWordChar(after.first())

        if (!hasLetterBefore) {
            return false
        }

        var i = before.length - 1
        while (i >= 0 && isVietnameseWordChar(before[i])) {
            i--
        }
        val wordBeforeRaw = before.substring(i + 1)

        var j = 0
        while (j < after.length && isVietnameseWordChar(after[j])) {
            j++
        }
        val wordAfterRaw = after.substring(0, j)

        val wordBeforeNfc = java.text.Normalizer.normalize(wordBeforeRaw, java.text.Normalizer.Form.NFC)
        val wordAfterNfc = java.text.Normalizer.normalize(wordAfterRaw, java.text.Normalizer.Form.NFC)
        val fullWordNfc = wordBeforeNfc + wordAfterNfc

        val commit = lastImeCommit
        val isRecentImeCommit = commit != null &&
                (System.currentTimeMillis() - commit.timestamp < 5000) &&
                (commit.word.equals(fullWordNfc, ignoreCase = true) || commit.word.equals(wordBeforeNfc, ignoreCase = true))

        if (!hasLetterAfter && !isRecentImeCommit) {
            return false
        }

        if (wordBeforeRaw.isNotEmpty() || wordAfterRaw.isNotEmpty()) {
            val isVni = (inputEngine.inputMethod == GoVietInputMethod.GoVietVni)
            val decomposedBefore = decomposeWord(wordBeforeNfc, isVni)
            val decomposedAfter = decomposeWord(wordAfterNfc, isVni)
            
            composingRaw.clear()
            composingRaw.append(decomposedBefore)
            composingRaw.append(decomposedAfter)
            composingCursorIndex = decomposedBefore.length
            val fresh = getFreshCursorPosition(ic)
            val currentSelStart = fresh?.first ?: service.currentSelStart
            composingStartInEditor = if (currentSelStart >= wordBeforeRaw.length) currentSelStart - wordBeforeRaw.length else -1
            inputEngine.invalidateCache()
            
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
            return true
        }
        return false
    }

    private fun isImmediateCommitMode(): Boolean {
        val editorInfo = service.currentInputEditorInfo ?: return false
        return editorInfo.inputType == android.text.InputType.TYPE_NULL
    }

    private fun isBypassVietnameseComposing(): Boolean {
        return typingMode == TypingMode.LATIN
    }

    private fun getFreshCursorPosition(ic: InputConnection): Pair<Int, Int>? {
        val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            ?: return null
        return Pair(extracted.selectionStart, extracted.selectionEnd)
    }

    private fun isComposingStateDesynced(ic: InputConnection): Boolean {
        if (composingRaw.isEmpty()) return false
        
        val expectedText = lastSetComposingText ?: ""
        if (expectedText.isEmpty()) return false
        val expectedNfc = java.text.Normalizer.normalize(expectedText, java.text.Normalizer.Form.NFC)
        
        val actualBefore = ic.getTextBeforeCursor(expectedText.length, 0)?.toString() ?: ""
        val liveSelected = ic.getSelectedText(0)?.toString() ?: ""
        val actualBeforeNfc = java.text.Normalizer.normalize(actualBefore, java.text.Normalizer.Form.NFC)
        
        if (actualBeforeNfc == expectedNfc && liveSelected.isEmpty()) {
            return false
        }

        if (System.currentTimeMillis() - lastKeyPressTime < 300) {
            return false
        }

        return true
    }

    private fun isVietnameseComposingKey(key: String): Boolean {
        if (service._languageMode.value == "ENG") return false
        if (isBypassVietnameseComposing()) return false
        if (key.length != 1) return false
        val char = key[0]
        if (char in 'a'..'z' || char in 'A'..'Z' || char.lowercaseChar() != char.uppercaseChar()) return true
        
        // In VNI, digits 0-9 are accent/diacritic inputs
        if (inputEngine.inputMethod == GoVietInputMethod.GoVietVni && char in '0'..'9') {
            return true
        }
        
        // In Telex, brackets [ and ] and shifted variants { and } are shortcut inputs for ư and ơ
        if (inputEngine.inputMethod == GoVietInputMethod.GoVietTelex && 
            (char == '[' || char == ']' || char == '{' || char == '}')) {
            return true
        }
        
        return false
    }

    private fun sendBackspaceEvents(ic: InputConnection, count: Int) {
        for (i in 0 until count) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    private fun sendDelKey(ic: InputConnection) {
        sendBackspaceEvents(ic, 1)
    }

    private fun clearComposingAndSendDelKey(ic: InputConnection) {
        ic.finishComposingText()
        clearState()
        sendDelKey(ic)
    }

    private fun resetComposingUI(ic: InputConnection, backspaceCountIfImmediate: Int = 0) {
        composingRaw.clear()
        activeComposingShiftState = 0
        lastSetComposingText = null
        if (isImmediateCommitMode()) {
            if (backspaceCountIfImmediate > 0) {
                sendBackspaceEvents(ic, backspaceCountIfImmediate)
            }
        } else {
            ic.setComposingText("", 1)
        }
    }

    private fun updateComposingUI(ic: InputConnection, lastLenIfImmediate: Int = 0) {
        val compiled = compileComposingText()
        val prefixRaw = composingRaw.substring(0, composingCursorIndex)
        val compiledPrefix = compileText(prefixRaw)
        if (isImmediateCommitMode()) {
            val lastStr = lastSetComposingText ?: ""
            if (lastStr.isNotEmpty() && compiled == lastStr.substring(0, lastStr.length - 1)) {
                sendBackspaceEvents(ic, 1)
            } else if (lastLenIfImmediate > 0) {
                sendBackspaceEvents(ic, lastLenIfImmediate)
            }
            ic.commitText(compiled, 1)
        } else {
            ic.setComposingText(compiled, 1)
        }
        if (composingCursorIndex != composingRaw.length && composingStartInEditor >= 0) {
            val newCursor = composingStartInEditor + compiledPrefix.length
            if (newCursor >= 0) {
                ic.setSelection(newCursor, newCursor)
            }
        }
        lastSetComposingText = compiled
    }

    private fun deleteLastGraphemeOrChar(ic: InputConnection) {
        val beforeText = ic.getTextBeforeCursor(20, 0)
        if (beforeText != null && beforeText.isNotEmpty()) {
            val text = beforeText.toString()
            val boundary = java.text.BreakIterator.getCharacterInstance()
            boundary.setText(text)
            val last = boundary.last()
            val previous = boundary.previous()
            if (previous != java.text.BreakIterator.DONE) {
                val charsToDelete = last - previous
                ic.deleteSurroundingText(charsToDelete, 0)
                return
            }
        }
        ic.deleteSurroundingText(1, 0)
    }

    private fun deleteNextGraphemeOrChar(ic: InputConnection) {
        val afterText = ic.getTextAfterCursor(20, 0)
        if (afterText != null && afterText.isNotEmpty()) {
            val text = afterText.toString()
            val boundary = java.text.BreakIterator.getCharacterInstance()
            boundary.setText(text)
            val first = boundary.first()
            val next = boundary.next()
            if (next != java.text.BreakIterator.DONE) {
                val charsToDelete = next - first
                ic.deleteSurroundingText(0, charsToDelete)
                return
            }
        }
        ic.deleteSurroundingText(0, 1)
    }

    fun getPrecedingWords(cachedBefore: CharSequence?): List<String> {
        val before = cachedBefore ?: return emptyList()
        val cleaned = CLEAN_LINE_REGEX.replace(before.toString().trim().lowercase(), "")
        return cleaned.split(SPLIT_WHITESPACE_REGEX).filter { it.isNotEmpty() }
    }

    fun getPrecedingWords(ic: InputConnection): List<String> {
        return getPrecedingWords(ic.getTextBeforeCursor(120, 0))
    }

    private fun performBackspaceOnComposingRaw() {
        if (composingCursorIndex > 0) {
            composingRaw.deleteAt(composingCursorIndex - 1)
            composingCursorIndex--
        }
    }

    private fun handleSeparator(ic: InputConnection, separator: String) {
        ic.beginBatchEdit()
        if (composingRaw.isNotEmpty()) {
            commitAndReset(wordBreak = separator)
        } else {
            commitAndReset()
            ic.commitText(separator, 1)
        }
        if (separator != " ") {
            recordImeCommit(separator)
        }
        ic.endBatchEdit()
        service.evaluateAutoShift()
    }

    fun handleKeyPress(key: String) {
        lastKeyPressTime = System.currentTimeMillis()
        val ic: InputConnection? = service.currentInputConnection
        if (ic == null) {
            Log.e(TAG, "handleKeyPress() FAILED - currentInputConnection is NULL")
            return
        }

        when (key) {
            "BACKSPACE" -> {
                val selected = ic.getSelectedText(0)
                val hasSelection = (selected != null && selected.isNotEmpty()) || (service.currentSelStart != service.currentSelEnd)
                if (hasSelection) {
                    clearComposingAndSendDelKey(ic)
                } else if (composingRaw.isNotEmpty()) {
                    if (isComposingStateDesynced(ic)) {
                        clearComposingAndSendDelKey(ic)
                        return
                    }
                    val lastLen = lastSetComposingText?.length ?: 0
                    performBackspaceOnComposingRaw()
                    if (composingRaw.isEmpty()) {
                        resetComposingUI(ic, lastLen)
                    } else {
                        updateComposingUI(ic, lastLen)
                    }
                } else {
                    ic.beginBatchEdit()
                    val beforeText = ic.getTextBeforeCursor(4, 0)
                    if (beforeText != null && beforeText.isNotEmpty() && isVietnameseWordChar(beforeText.last())) {
                        val adopted = adoptWordAtCursor(ic)
                        if (adopted && composingRaw.isNotEmpty()) {
                            performBackspaceOnComposingRaw()
                            if (composingRaw.isEmpty()) {
                                resetComposingUI(ic, 1)
                            } else {
                                updateComposingUI(ic, 0)
                            }
                        } else {
                            sendDelKey(ic)
                        }
                    } else {
                        sendDelKey(ic)
                    }
                    ic.endBatchEdit()
                }
                service.evaluateAutoShift()
            }
            "DELETE_WORD" -> {
                if (composingRaw.isNotEmpty() && isComposingStateDesynced(ic)) {
                    ic.finishComposingText()
                    clearState()
                }
                if (composingRaw.isNotEmpty()) {
                    val lastLen = lastSetComposingText?.length ?: 0
                    resetComposingUI(ic, lastLen)
                } else {
                    ic.beginBatchEdit()
                    val beforeText = ic.getTextBeforeCursor(100, 0) ?: ""
                    if (beforeText.isNotEmpty()) {
                        val trimmed = beforeText.toString().trimEnd()
                        val lastSpaceIndex = trimmed.lastIndexOf(' ')
                        val charsToDelete = beforeText.length - (if (lastSpaceIndex == -1) 0 else lastSpaceIndex)
                        if (isImmediateCommitMode()) {
                            sendBackspaceEvents(ic, charsToDelete)
                        } else {
                            ic.deleteSurroundingText(charsToDelete, 0)
                        }
                    } else {
                        deleteLastGraphemeOrChar(ic)
                    }
                    ic.endBatchEdit()
                }
                service.evaluateAutoShift()
            }
            "," -> handleSeparator(ic, ",")
            "." -> handleSeparator(ic, ".")
            "SPACE" -> handleSeparator(ic, " ")
            "ENTER" -> {
                ic.beginBatchEdit()
                commitAndReset()
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
                ic.endBatchEdit()
                service.evaluateAutoShift()
            }
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
                val now = System.currentTimeMillis()
                lastShiftTime = service.shiftController.toggleShiftKey(now, lastShiftTime)
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
                if (!isVietnameseComposingKey(key)) {
                    ic.beginBatchEdit()
                    commitAndReset()
                    ic.commitText(actualKey, 1)
                    service.lastCommittedWord = actualKey
                    service.shiftController.consumeSingleShift()
                    ic.endBatchEdit()
                } else {
                    ic.beginBatchEdit()
                    if (composingRaw.isNotEmpty() && isComposingStateDesynced(ic)) {
                        ic.finishComposingText()
                        clearState()
                    }
                    if (composingRaw.isEmpty()) {
                        val adopted = adoptWordAtCursor(ic)
                        if (!adopted) {
                            val fresh = getFreshCursorPosition(ic)
                            val currentSel = fresh?.first ?: service.currentSelStart
                            composingStartInEditor = if (currentSel >= 0) currentSel else -1
                            composingCursorIndex = 0
                        }
                    }
                    if (composingRaw.isEmpty()) {
                        activeComposingShiftState = service.shiftController.value
                    }
                    val lastLen = lastSetComposingText?.length ?: 0
                    composingRaw.insert(composingCursorIndex, actualKey)
                    composingCursorIndex += actualKey.length

                    updateComposingUI(ic, lastLen)
                    if (isImmediateCommitMode()) {
                        val compiled = lastSetComposingText ?: ""
                        recordImeCommit(compiled)
                    }
                    service.shiftController.consumeSingleShift()
                    ic.endBatchEdit()
                }
            }
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
                        deleteNextGraphemeOrChar(ic)
                    }
                }
            }
        }
    }

    private fun compileComposingText(): String {
        return compileText(composingRaw.toString())
    }

    private fun compileText(raw: String): String {
        val compiled = inputEngine.process(raw)
        return GoVietCharUtils.applyCasingFromRaw(compiled, raw)
    }

    private fun tryExpandMacro(raw: String, wordBreak: String): String? {
        if (!inputEngine.macroEnabled) return null
        val store = inputEngine.macroStore ?: return null
        if (store.isEmpty()) return null

        // Case 1: trigger không dấu, phổ biến nhất (vd "rs" -> "RoSino18k")
        store.lookup(raw.lowercase())?.let { expansion ->
            return applyMacroCase(expansion, raw) + wordBreak
        }

        // Case 2: trigger có dấu tiếng Việt — chỉ áp dụng khi bật "GÕ TẮT VIỆT" (alwaysMacro)
        if (inputEngine.alwaysMacro) {
            val composed = compileText(raw)   // KHÔNG kèm wordBreak — an toàn, không đụng processWordBoundary
            if (composed != raw) {
                store.lookup(composed.lowercase())?.let { expansion ->
                    return applyMacroCase(expansion, composed) + wordBreak
                }
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
                if (isComposingStateDesynced(ic)) {
                    ic.finishComposingText()
                    clearState()
                    return
                }
                val raw = composingRaw.toString()
                val outputText = tryExpandMacro(raw, wordBreak)
                    ?: (compileText(raw) + wordBreak)

                if (isImmediateCommitMode()) {
                    val lastLen = lastSetComposingText?.length ?: 0
                    if (lastLen > 0) {
                        sendBackspaceEvents(ic, lastLen)
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
            }
            clearState()
            service.evaluateAutoShift()
        }
    }

    fun commitAndFinishing(wordBreak: String = "") = commitAndReset(wordBreak)
}
