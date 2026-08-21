package com.goviet.keyboard.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controller for managing shift / caps lock state cleanly and consistently.
 * Values:
 * 0 = Lowercase
 * 1 = Single uppercase shift
 * 2 = Caps lock
 */
class ShiftStateController(
    private val _state: MutableStateFlow<Int> = MutableStateFlow(0)
) {
    val state: StateFlow<Int> = _state.asStateFlow()

    var value: Int
        get() = _state.value
        set(v) { _state.value = v }

    val isShifted: Boolean get() = _state.value > 0
    val isSingleShift: Boolean get() = _state.value == 1
    val isCapsLock: Boolean get() = _state.value == 2

    /**
     * Consume single shift state after a letter key is typed or committed.
     * Keeps Caps Lock intact if active.
     */
    fun consumeSingleShift() {
        if (_state.value == 1) {
            _state.value = 0
        }
    }

    /**
     * Triggered when sentence start condition is detected.
     * Sets single shift if not already in Caps Lock.
     */
    fun onSentenceStartDetected() {
        if (_state.value != 2) {
            _state.value = 1
        }
    }

    /**
     * Triggered when cursor moves away from sentence start or auto-capitalize is lost.
     */
    fun onSentenceStartLost() {
        if (_state.value == 1) {
            _state.value = 0
        }
    }

    /**
     * Toggle shift key on soft keyboard key press.
     * Double-tap within doubleTapTimeoutMs turns on Caps Lock.
     */
    fun toggleShiftKey(now: Long, lastShiftTime: Long, doubleTapTimeoutMs: Long = 300L): Long {
        return if (_state.value == 2) {
            _state.value = 0
            0L
        } else if (now - lastShiftTime < doubleTapTimeoutMs) {
            _state.value = 2 // Caps Lock
            0L
        } else {
            _state.value = if (_state.value == 0) 1 else 0
            now
        }
    }

    fun forceCapsLock() {
        _state.value = 2
    }

    fun reset() {
        _state.value = 0
    }
}
