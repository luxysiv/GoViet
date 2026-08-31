package com.goviet.keyboard.engine

import java.text.BreakIterator

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
