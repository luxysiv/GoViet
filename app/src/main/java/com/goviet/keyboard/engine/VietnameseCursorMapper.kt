package com.goviet.keyboard.engine

/**
 * Unified bidirectional cursor mapping between display text offset and raw Telex buffer index.
 * Uses linear single-pass indexing to achieve O(1) cursor lookups without O(n^2) substring compilations.
 * Shared across:
 * - Middle-word typing (inserting characters at cursor)
 * - Backspace (backward deletion at cursor)
 * - Delete (forward deletion at cursor)
 * - User touch cursor repositioning (onUpdateSelection)
 * - Word adoption at arbitrary cursor offset
 */
object VietnameseCursorMapper {

    private val threadLocalComposer = object : ThreadLocal<VietnameseComposer>() {
        override fun initialValue(): VietnameseComposer {
            return VietnameseComposer(EngineOptions())
        }
    }

    /**
     * Precomputed O(1) bidirectional mapping between raw buffer index and display offset.
     */
    data class CursorMapping(
        val rawToDisplayMap: IntArray,
        val displayToRawMap: IntArray
    ) {
        fun rawToDisplay(rawCursor: Int): Int {
            if (rawToDisplayMap.isEmpty()) return 0
            val idx = rawCursor.coerceIn(0, rawToDisplayMap.size - 1)
            return rawToDisplayMap[idx]
        }

        fun displayToRaw(displayOffset: Int): Int {
            if (displayToRawMap.isEmpty()) return 0
            val idx = displayOffset.coerceIn(0, displayToRawMap.size - 1)
            return displayToRawMap[idx]
        }
    }

    /**
     * Builds a bidirectional CursorMapping in a single linear O(N) pass.
     */
    fun buildMapping(
        raw: String,
        display: String = "",
        ownership: CompositionOwnership = CompositionOwnership.LIVE_VIETNAMESE,
        options: EngineOptions = EngineOptions()
    ): CursorMapping {
        if (raw.isEmpty()) {
            return CursorMapping(IntArray(1) { 0 }, IntArray(1) { 0 })
        }

        if (ownership == CompositionOwnership.EDITED_LITERAL) {
            val map = IntArray(raw.length + 1) { it }
            return CursorMapping(map, map)
        }

        val rawToDisplayMap = IntArray(raw.length + 1)
        rawToDisplayMap[0] = 0

        val composer = threadLocalComposer.get() ?: VietnameseComposer(options)
        composer.options = options
        composer.reset()
        for (i in 0 until raw.length) {
            composer.processKey(raw[i])
            rawToDisplayMap[i + 1] = composer.toDisplayString().length
        }

        val finalDisplay = if (display.isNotEmpty()) display else composer.toDisplayString()
        val displayLen = finalDisplay.length
        val displayToRawMap = IntArray(displayLen + 1)

        var rawIdx = 0
        for (d in 0 until displayLen) {
            while (rawIdx < raw.length && rawToDisplayMap[rawIdx] < d) {
                rawIdx++
            }
            displayToRawMap[d] = rawIdx
        }
        displayToRawMap[displayLen] = raw.length

        return CursorMapping(rawToDisplayMap, displayToRawMap)
    }

    /**
     * Maps a cursor offset in the display string to the corresponding cursor offset in the raw Telex string.
     */
    fun displayToRaw(
        raw: String,
        display: String,
        displayOffset: Int,
        ownership: CompositionOwnership = CompositionOwnership.LIVE_VIETNAMESE,
        options: EngineOptions = EngineOptions()
    ): Int {
        if (displayOffset <= 0 || raw.isEmpty()) return 0
        if (ownership == CompositionOwnership.EDITED_LITERAL) {
            return displayOffset.coerceIn(0, raw.length)
        }
        if (displayOffset >= display.length) {
            return raw.length
        }

        val mapping = buildMapping(raw, display, ownership, options)
        return mapping.displayToRaw(displayOffset)
    }

    /**
     * Maps a cursor index in the raw Telex buffer to the corresponding cursor offset in the display string.
     */
    fun rawToDisplay(
        raw: String,
        rawCursor: Int,
        ownership: CompositionOwnership = CompositionOwnership.LIVE_VIETNAMESE,
        options: EngineOptions = EngineOptions()
    ): Int {
        if (rawCursor <= 0 || raw.isEmpty()) return 0
        if (ownership == CompositionOwnership.EDITED_LITERAL) {
            return rawCursor.coerceIn(0, raw.length)
        }
        if (rawCursor >= raw.length) {
            val composer = threadLocalComposer.get() ?: VietnameseComposer(options)
            composer.options = options
            composer.reset()
            return composer.processString(raw).length
        }

        val mapping = buildMapping(raw, "", ownership, options)
        return mapping.rawToDisplay(rawCursor)
    }
}
