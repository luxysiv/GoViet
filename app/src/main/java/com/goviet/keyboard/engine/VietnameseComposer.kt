package com.goviet.keyboard.engine

import android.content.Context
import com.goviet.core.AppPreferences
import com.goviet.core.EngineConfig

/**
 * VietnameseComposer — Single-resegment Telex engine.
 *
 * All syllable segmentation is derived by the single `resegment` function.
 * The instance owns the composing preedit buffer and derives the display from
 * it through the session API below; the IME controller never keeps a second
 * copy of the composing state.
 */
class VietnameseComposer(var options: EngineOptions = EngineOptions()) {

    var vietnameseModeEnabled: Boolean = true
    var autoCapitalize: Boolean = false

    /**
     * Adopt-path scratch, owned per composer so it stays thread-confined like
     * the rest of the engine (see [OwnedBuffer]).  [canonicalScratch] accumulates
     * the canonical raw; [plainScratch] holds the unfolded nucleus on the
     * fold-last path, which is a separate buffer because it is still being read
     * while the canonical raw is written.
     */
    private val canonicalScratch = OwnedBuffer()
    private val plainScratch = OwnedBuffer()

    /**
     * Display form of the live nucleus, captured before a fold mutates it (see
     * [FoldAnchor.plainNucleus]).  Owned per composer for the same reason as the
     * other scratch buffers.
     */
    private val nucleusDisplayScratch = OwnedBuffer()

    /**
     * Display form of a parked nucleus, the input the fold data is keyed on (see
     * [applyFoldRules]).  Separate from [nucleusDisplayScratch] because the
     * caller still holds the pre-fold display there while the fold runs.
     */
    private val foldNucScratch = OwnedBuffer()

    var macroEnabled: Boolean
        get() = options.macroEnabled
        set(v) { options.macroEnabled = v }

    var directW: Boolean
        get() = options.directW
        set(v) { options.directW = v }

    var oldTonePlacement: Boolean
        get() = options.oldTonePlacement
        set(v) { options.oldTonePlacement = v }

    class SyllableState(
        var onset: OwnedBuffer = OwnedBuffer(),
        var nucleus: OwnedBuffer = OwnedBuffer(),
        var coda: OwnedBuffer = OwnedBuffer(),
        var tone: Tone = Tone.NONE,
        var rawSuffix: OwnedBuffer = OwnedBuffer(),
        /**
         * The nucleus is a parked alias: [nucleus] holds the RESOLVED rime
         * ("ươ") while the user has only committed to the parked display
         * ("ưo").  Keeping the resolved rime in the buffer is what lets every
         * packed key equal the key of the buffer it describes; the parked
         * spelling comes back only at render time, and when folding.
         *
         * Invariant: parked ⟹ no coda and no tone.  Every path that adds a coda
         * ([tryCoda]) or a tone ([handleToneKey], [applyPendingTone]) unpark first,
         * and no modifier key can extend "ươ", so the two cannot coexist.
         */
        var parked: Boolean = false
    ) {
        /** Per-state render scratch — avoids allocating an OwnedBuffer per display. */
        private val displayScratch = OwnedBuffer()

        fun reset() {
            onset.clear()
            nucleus.clear()
            coda.clear()
            tone = Tone.NONE
            rawSuffix.clear()
            parked = false
        }
        fun isEmpty(): Boolean = onset.isEmpty() && nucleus.isEmpty() && coda.isEmpty() && rawSuffix.isEmpty()

        /**
         * Append the nucleus as the user sees it.  A parked alias renders as its
         * parked display ("ưo") even though the buffer holds "ươ"; everything
         * else appends verbatim.  Case is carried over position by position, the
         * way a fold carries it, so an upper-case pivot over a lower-case
         * follower ("Ưo") renders "Ưo" instead of flattening to "ƯO".
         */
        fun appendNucleusDisplay(out: OwnedBuffer) {
            if (!parked) {
                out.append(nucleus)
                return
            }
            val chars = RimeMap.parkedDisplayChars(nucleus)
            if (chars == null) {
                out.append(nucleus)
                return
            }
            for (i in chars.indices) {
                val ch = chars[i]
                out.append(if (i < nucleus.length && nucleus[i].isUpperCase()) ch.uppercaseChar() else ch)
            }
        }

        fun toDisplayString(oldTonePlacement: Boolean = false): String {
            toDisplayBuffer(displayScratch, oldTonePlacement)
            return displayScratch.toStringVal()
        }

        fun toDisplayBuffer(out: OwnedBuffer, oldTonePlacement: Boolean = false) {
            out.clear()
            if (isEmpty()) return
            // Parked ⟹ no coda and no tone, so the parked display is the whole
            // nucleus and goes out verbatim; handled before the tone branch so a
            // violated invariant could not silently render the resolved rime.
            if (parked) {
                out.append(onset)
                appendNucleusDisplay(out)
                out.append(rawSuffix)
                return
            }
            if (tone == Tone.NONE || nucleus.isEmpty()) {
                out.append(onset)
                appendNucleusDisplay(out)
                out.append(coda)
                out.append(rawSuffix)
                return
            }
            val rimeKey = RimeMap.keyCat(nucleus, nucleus.length, coda, coda.length)
            var toneIdx = RimeMap.determineTonePosition(
                rimeKey, oldTonePlacement, nucleus.length)
            if (coda.isEmpty() && rawSuffix.isNotEmpty()) {
                val pending = pendingFoldCodaIndex()
                if (pending >= 0) toneIdx = pending
            }
            out.append(onset)
            for (i in 0 until nucleus.length) {
                if (i == toneIdx) out.append(VietnameseUnicode.applyTone(nucleus[i], tone))
                else out.append(nucleus[i])
            }
            out.append(coda)
            out.append(rawSuffix)
        }

        /**
         * When a tone is set but the following consonant was rejected as a coda
         * (the current nucleus cannot host it — e.g. "ua" + "n"), the tone mark
         * stays on the first vowel even though the pending fold would move it.
         * If a Telex fold key could turn this tail into a valid coda (ua + n ->
         * uâ + n via 'a'), anchor the mark on the last nucleus vowel instead:
         * churan -> chuản, then churana -> chuẩn.
         */
        private fun pendingFoldCodaIndex(): Int {
            if (nucleus.isEmpty() || coda.isNotEmpty() || rawSuffix.isEmpty()) return -1
            val c0 = rawSuffix[0].lowercaseChar()
            if (c0 != 'm' && c0 != 'p' && c0 != 'n' && c0 != 't' && c0 != 'c') return -1
            val nucStr = nucleus.toStringVal()
            val key = RimeMap.rimeKey(nucStr)
            for (fk in RimeMap.VOWEL_MOD_KEYS) {
                if (RimeMap.foldCodaValid(nucStr, key, fk, c0) != null) {
                    return (nucStr.length - 1).coerceAtLeast(0)
                }
            }
            return -1
        }
    }

    data class AdoptResult(
        val isValid: Boolean,
        val onsetLength: Int,
        val canonicalRaw: String,
        val canonicalFoldLast: String? = null
    )

    sealed class CompositionResult {
        abstract val text: CharSequence
        data class Update(override val text: CharSequence) : CompositionResult()
        data class CommitAndStartNew(val commitText: String, val newChar: Char) : CompositionResult() {
            override val text: CharSequence get() = commitText
        }
    }

    private val replayState = SyllableState()
    private val stringOut = OwnedBuffer()
    private val scanCtx = ScanCtx(0)

    /** Test API: internal buffer + state for processKey. */
    private val processRaw = StringBuilder()
    private val processState = SyllableState()
    private val syllableRenderBuf = OwnedBuffer()

    fun reset() {
        replayState.reset()
        processRaw.clear()
        processState.reset()
        composeAsVietnamese = true
    }
    /** composeAsVietnamese flag — used by tests to switch Vietnamese/Literal mode. */
    var composeAsVietnamese: Boolean = true

    /** Display string of the current interactive state. */
    fun toDisplayString(): String =
        processState.toDisplayString(options.oldTonePlacement)

    /** Render the current interactive state into [out] without allocating a String. */
    fun toDisplayBuffer(out: OwnedBuffer) {
        processState.toDisplayBuffer(out, options.oldTonePlacement)
    }

    /**
     * Generate deconstructed snapshots: adopt [word], replay keystroke by keystroke,
     * return (canonicalRaw, snapshots).
     */
    internal fun generateDeconstructedSnapshots(word: String): Pair<String, List<Snapshot>> {
        val adopt = adoptWord(word) ?: return Pair(word, listOf(Snapshot(word)))
        val canonical = canonicalRawIfRoundTrips(adopt, word) ?: adopt.canonicalRaw
        val snaps = mutableListOf<Snapshot>()
        val tempState = SyllableState()
        for (i in 0 until canonical.length) {
            resegment(canonical.subSequence(0, i + 1), tempState)
            snaps.add(Snapshot(tempState.toDisplayString(options.oldTonePlacement)))
        }
        return Pair(canonical, snaps)
    }

    data class Snapshot(val displayText: String)

    /**
     * Lookahead in raw: consonant chars from [from] that could form a coda,
     * tone/fold keys skipped without lengthening the tail — so a tone typed
     * mid-read (huownsg) still yields "ng" and the w-fold resolves to ươ.
     */
    private fun predictConsonantTail(raw: CharSequence, from: Int): String {
        val sb = StringBuilder()
        var i = from
        while (i < raw.length) {
            val c = raw[i].lowercaseChar()
            if (isToneKey(c) || RimeMap.isFoldKey(c)) { i++; continue }
            if (OnsetMap.isConsonant(c)) {
                sb.append(c)
                i++
            } else break
        }
        return sb.toString()
    }

    /**
     * Collect vowels immediately after [from] that would extend the nucleus
     * (plain vowels a/e/i/o/u/y + horned â/ô/ơ/ư). Used by the dual-variant
     * w-fold to validate the fold result against the nucleus extension that
     * will follow, so the correct variant is chosen even when both are valid
     * standalone.
     */
    private fun predictVowelTail(raw: CharSequence, from: Int): String {
        val sb = StringBuilder()
        var i = from
        while (i < raw.length) {
            val c = raw[i].lowercaseChar()
            if (RimeMap.isBaseVowel(c)) { sb.append(c); i++ }
            else break
        }
        return sb.toString()
    }

    /**
     * Pipeline entry — single source of truth for syllable composition.
     *
     * Phase 1 [matchOnset] consumes the longest valid consonant prefix.
     * Phase 2 [scanBody] walks the remaining raw keys left-to-right, dispatching
     * each character to the appropriate handler (tone / modifier / vowel /
     * coda) and applying folds from the map data.  Phase 3 renders [SyllableState]
     * to display text (see [SyllableState.toDisplayString]).
     *
     * No incremental mutation survives between keystrokes: the controller appends
     * to the raw buffer and calls resegment again, so every state is derived.
     */
    private fun resegment(raw: CharSequence, out: SyllableState) {
        out.reset()
        if (raw.isEmpty()) return
        matchOnset(raw, out)
        scanCtx.reset(if (out.onset.isEmpty()) 0 else OnsetMap.onsetKeyOf(out.onset))
        scanBody(raw, out, scanCtx)
    }

    /** Test API: resegment [raw] into a fresh state (the resegment the kernel
     *  uses for every replay); mirrors [resegment] for display-level tests. */
    internal fun replayRawToState(raw: CharSequence, out: SyllableState) {
        resegment(raw, out)
    }


    /**
     * Fold anchor — the single record of the last Telex fold that touched the
     * nucleus.  Untoggle compares the next key against it; it replaces the old
     * loose fold-key/nucleus-index/raw-position/standalone fields.
     */
    private class FoldAnchor(
        var key: Char = '\u0000',
        var rawPos: Int = -1,
        var standalone: Boolean = false,
        /**
         * The nucleus as the user saw it BEFORE the fold fired, captured in its
         * display form — "ưo" for a parked alias, not the resolved "ươ" the
         * state holds.  The untoggle guard compares the live nucleus against it
         * to tell "the fold still stands" from "the user kept typing", so a
         * resolved form here would make the guard always fire.
         */
        val plainNucleus: OwnedBuffer = OwnedBuffer()
    ) {
        val active: Boolean get() = key != '\u0000'
        fun set(key: Char, rawPos: Int, plainNucleus: OwnedBuffer? = null, standalone: Boolean = false) {
            this.key = key; this.rawPos = rawPos
            this.standalone = standalone
            this.plainNucleus.clear()
            if (plainNucleus != null) this.plainNucleus.setTo(plainNucleus)
        }
        fun clear() { key = '\u0000'; rawPos = -1; standalone = false; plainNucleus.clear() }
    }

    /** Per-call scan memory — 6 fields + fold anchor (was 12 loose fields).
     *  Reused across resegments (see [scanCtx]) so no object is allocated on
     *  the per-keypress path.  Only safe because resegment is never re-entrant. */
    private class ScanCtx(
        var oKey: Int,
        var nucKey: Int = 0,
        var rimeKey: Int = 0,
        var lastToneKey: Char = '\u0000',
        /**
         * The syllable is still open for a Telex transition.  Named in the
         * positive so the many guard sites read as one question ("is this still
         * open?") instead of a double negative; [lockLiteral] is the only way to
         * close it.
         */
        var open: Boolean = true,
        var justUntoggled: Boolean = false,
        val fold: FoldAnchor = FoldAnchor(),
        var pendingTone: Tone = Tone.NONE,
        var pendingToneKey: Char = '\u0000'
    ) {
        fun reset(oKey: Int) {
            this.oKey = oKey
            nucKey = 0
            rimeKey = 0
            lastToneKey = '\u0000'
            open = true
            justUntoggled = false
            fold.clear()
            pendingTone = Tone.NONE
            pendingToneKey = '\u0000'
        }

        /**
         * Re-derive [nucKey] and [rimeKey] after the nucleus changed — the one
         * place that invariant lives.  Every mutation of `out.nucleus` must go
         * through this, or the packed keys silently describe the old nucleus
         * and the next coda/tone decision is made on stale data.
         *
         * No candidate key is needed: a parked alias keeps the RESOLVED rime in
         * [SyllableState.nucleus], so the key of the buffer is already the key
         * the extension math must run on and there is nothing to substitute.
         */
        fun repackNucleus(out: SyllableState) {
            nucKey = RimeMap.rimeKey(out.nucleus)
            rimeKey = RimeMap.extendKey(nucKey, out.coda, 0, out.coda.length)
        }
    }

    /**
     * Phase 1 — longest valid onset prefix.  Single vowels are never onsets; with
     * directW off a leading 'w' folds instead; "gi" only wins as an onset when a
     * vowel follows (otherwise its 'i' becomes the nucleus: gif → g + i + f).
     */
    private fun matchOnset(raw: CharSequence, out: SyllableState) {
        val len = raw.length
        if (len == 0 || !OnsetMap.isConsonant(raw[0])) return
        val maxOnset = minOf(3, len)
        var onsetEnd = 0
        for (onsetLen in maxOnset downTo 1) {
            if (OnsetMap.isCompleteOnset(raw, 0, onsetLen)) {
                if (onsetLen == 1 && RimeMap.isBaseVowel(raw[0])) continue
                if (!options.directW && onsetLen == 1 && raw[0].lowercaseChar() == 'w') continue
                if (onsetLen > 1 && OnsetMap.isOnsetNeedingVowel(raw, 0, onsetLen)) {
                    var vowelAfter = false
                    for (k in onsetLen until len) {
                        val ch = raw[k].lowercaseChar()
                        if (RimeMap.isBaseVowel(ch) || ch == 'w') { vowelAfter = true; break }
                    }
                    if (!vowelAfter) continue
                }
                onsetEnd = onsetLen
                break
            }
        }
        if (onsetEnd > 0) out.onset.setTo(raw, 0, onsetEnd)
    }

    /** Append [c] as literal text and hard-lock the rest of the syllable. */
    private fun lockLiteral(out: SyllableState, ctx: ScanCtx, c: Char) {
        out.rawSuffix.append(c)
        ctx.open = false
    }

    /**
     * Phase 2 — walk the body keys, dispatching by category.  Each handler owns
     * exactly one branch; `ScanCtx` carries the scan memory.
     *
     * Classify once per key, transition once: precedence is tone > fold >
     * vowel > consonant > literal (a key may carry several bits, e.g. 'y'
     * is both vowel and consonant).
     */
    private fun scanBody(raw: CharSequence, out: SyllableState, ctx: ScanCtx) {
        var pos = out.onset.length
        val len = raw.length
        while (pos < len) {
            val c = raw[pos]
            val cLow = c.lowercaseChar()

            if (tryOnsetFold(c, cLow, out, ctx)) { pos++; continue }

            // Classify once; every branch below reads the same bitmask.
            val cat = keyCat(cLow)
            when {
                cat and CAT_TONE != 0 -> {
                    handleToneKey(c, cLow, out, ctx)
                    pos++
                }
                cat and CAT_FOLD != 0 -> {
                    if (cLow == 'w') handleWKey(raw, c, pos, out, ctx)
                    else handleModifierKey(raw, c, cLow, pos, out, ctx)
                    pos++
                }
                cat and CAT_VOWEL != 0 &&
                    ctx.open &&
                    tryPlainVowel(c, out, ctx) -> {
                    pos++
                }
                cat and CAT_CONSONANT != 0 &&
                    ctx.open &&
                    out.nucleus.isNotEmpty() &&
                    tryCoda(c, out, ctx) -> {
                    pos++
                }
                else -> {
                    lockLiteral(out, ctx, c)
                    pos++
                }
            }
        }
    }

    /** Onset fold handler — returns true when the key was consumed by d→đ or untoggle. */
    private fun tryOnsetFold(c: Char, cLow: Char, out: SyllableState, ctx: ScanCtx): Boolean {
        if (!ctx.open || out.onset.isEmpty() || !OnsetMap.isRegisteredFoldKey(cLow)) return false
        val oFold = OnsetMap.foldTarget(ctx.oKey, cLow)
        if (oFold != 0) {
            out.onset.setTo(OnsetMap.applyFold(out.onset, oFold))
            ctx.oKey = OnsetMap.onsetKeyOf(out.onset)
            return true
        }
        val ufKey = OnsetMap.foldKeyForTarget(ctx.oKey)
        if (ufKey != '\u0000' && cLow == ufKey) {
            out.onset.setTo(OnsetMap.unfoldOnset(out.onset, ufKey))
            ctx.oKey = OnsetMap.onsetKeyOf(out.onset)
            lockLiteral(out, ctx, c)
            return true
        }
        return false
    }

    /** Tone handler — applies, clears, or untoggles the tone; locks on rejection. */
    private fun handleToneKey(c: Char, cLow: Char, out: SyllableState, ctx: ScanCtx) {
        if (!ctx.open) {
            out.rawSuffix.append(c)
            return
        }
        val targetTone = Tone.fromKey(cLow)
        if (targetTone != null && out.nucleus.isNotEmpty()) {
            if (out.nucleus.length >= 2) {
                val n0 = out.nucleus[0].lowercaseChar()
                val n1 = out.nucleus[1].lowercaseChar()
                if ((n0 == 'a' && n1 == 'a') || (n0 == 'e' && n1 == 'e')) {
                    lockLiteral(out, ctx, c)
                    return
                }
            }
            if (targetTone == Tone.NONE && out.tone == Tone.NONE) {
                lockLiteral(out, ctx, c)
                return
            }
            if (targetTone == Tone.NONE && out.tone != Tone.NONE) {
                out.tone = Tone.NONE
                ctx.lastToneKey = '\u0000'
                return
            }
            if (ctx.lastToneKey != '\u0000' && cLow == ctx.lastToneKey) {
                if (out.tone != Tone.NONE) {
                    out.tone = Tone.NONE
                    ctx.lastToneKey = '\u0000'
                }
                lockLiteral(out, ctx, c)
                return
            }
            val rk = ctx.rimeKey
            if (RimeMap.isRimeKeyValidForTone(rk, targetTone)) {
                unparkNucleus(out)
                out.tone = targetTone
                ctx.lastToneKey = cLow
            } else {
                lockLiteral(out, ctx, c)
            }
            return
        }
        if (out.nucleus.isEmpty() && OnsetMap.isOnsetParkingEarlyTone(out.onset)) {
            if (targetTone != null && targetTone != Tone.NONE) {
                if (ctx.pendingTone != Tone.NONE && cLow == ctx.pendingToneKey) {
                    ctx.pendingTone = Tone.NONE; ctx.pendingToneKey = '\u0000'
                } else {
                    ctx.pendingTone = targetTone; ctx.pendingToneKey = cLow
                }
                return
            }
            if (targetTone == Tone.NONE && ctx.pendingTone != Tone.NONE) {
                ctx.pendingTone = Tone.NONE; ctx.pendingToneKey = '\u0000'
                return
            }
        }
        lockLiteral(out, ctx, c)
    }

    /**
     * Letter that a repeated fold key unfolds back to — plain base of [nuc].
     * Takes a [CharSequence] so the live nucleus buffer can be unfolded without
     * a snapshot String on the per-keystroke path.
     */
    private fun unfoldToBase(nuc: CharSequence): String {
        var changed = -1
        for (i in 0 until nuc.length) {
            if (RimeMap.plainOf(nuc[i]) != nuc[i]) { changed = i; break }
        }
        if (changed < 0) return nuc.toString()
        val sb = StringBuilder(nuc.length)
        for (i in 0 until nuc.length) sb.append(RimeMap.plainOf(nuc[i]))
        return sb.toString()
    }

    /**
     * True when every key typed between the anchored fold at [foldPos] and [pos]
     * was absorbed as a coda consonant or as a closing semivowel (the final
     * vowel of the nucleus) and nothing else.  A fold key repeated in that state
     * is a *doubled* key, not a second fold request, so the pending fold is
     * released exactly like the adjacent "aaa → aa" case — the same rule with
     * the coda/semivowel tail: buoono → buono, leenhe → lenhe, daaua → daua.
     *
     * A tone key in the tail is transparent.  A tone marks the nucleus, it does
     * not build the syllable, so it can no more interrupt the tail than a coda
     * can.  Every tone key is treated alike — that is what keeps the fold rule
     * in sync across the whole transforming set, whether the tone letter also
     * happens to be an onset consonant ('s', 'r', 'x') or not ('f', 'z'):
     * aansa → ána, oofngo → òngo, deepseel → dépeel.
     *
     * Any other key (another fold key, or a rejected letter) locks the syllable,
     * which keeps it out of the untoggle path altogether.
     */
    private fun isFoldTailCodaOrSemivowel(raw: CharSequence, foldPos: Int, pos: Int): Boolean {
        if (pos <= foldPos + 1) return false
        for (i in foldPos + 1 until pos) {
            val c = raw[i].lowercaseChar()
            if (RimeMap.isBaseVowel(c)) continue
            if (RimeMap.isToneKey(c)) continue
            if (OnsetMap.isConsonant(c)) continue
            return false
        }
        return true
    }

    /**
     * 'w'-key handler — w-special rules live here (standalone w → ư when directW
     * is off); a repeated 'w' after an applied fold falls through to the shared
     * untoggle path (like d→đ and the tone keys), releasing the fold and letting
     * the extra 'w' out as literal text: uoww → uow, thuowwngs → thuowngs.
     * Everything else goes to the shared fold path so aw→ă, ow→ơ, uw→ư work in
     * both directW modes.
     */
    private fun handleWKey(raw: CharSequence, c: Char, pos: Int, out: SyllableState, ctx: ScanCtx) {
        if (!options.directW && ctx.open && out.nucleus.isEmpty() &&
            (out.onset.isEmpty() || out.onset[0].lowercaseChar() != 'w')) {
            val startFold = RimeMap.startFoldChar(c)
            if (startFold != '\u0000') {
                val wChar = if (c.isUpperCase()) startFold.uppercaseChar() else startFold
                val comboOk = out.onset.isEmpty() ||
                    RimeMap.isSyllableDisplayPrefixValid(out.onset, wChar)
                if (comboOk) {
                    out.nucleus.setTo(wChar)
                    unparkNucleus(out)
                    ctx.repackNucleus(out)
                    ctx.fold.set('w', pos, standalone = true)
                    applyPendingTone(out, ctx)
                    return
                }
            }
        }
        handleModifierKey(raw, c, 'w', pos, out, ctx)
    }

    /**
     * Shared modifier-key machinery — untoggle, fold, vowel combination,
     * plain extension, literal fallback.  Always consumes the key.
     *
     * The caller guarantees [cLow] is a fold key (scanBody dispatches here
     * only on CAT_FOLD; handleWKey passes a literal 'w'), so no second
     * fold-key lookup is needed here.
     */
    private fun handleModifierKey(raw: CharSequence, c: Char, cLow: Char, pos: Int, out: SyllableState, ctx: ScanCtx) {
        if (ctx.open && out.nucleus.isNotEmpty() && !ctx.justUntoggled) {
            if (ctx.fold.active && cLow == ctx.fold.key &&
                (pos == ctx.fold.rawPos + 1 ||
                    (cLow == 'w' && out.coda.isNotEmpty() && pos > ctx.fold.rawPos) ||
                    isFoldTailCodaOrSemivowel(raw, ctx.fold.rawPos, pos)) &&
                !out.nucleus.contentEquals(ctx.fold.plainNucleus)) {
                if (ctx.fold.standalone) {
                    out.nucleus.clear()
                    unparkNucleus(out)
                    out.rawSuffix.append(c)
                    ctx.open = false
                } else {
                    // Release the fold on the *live* nucleus: on the adjacent
                    // paths it is still the folded plainNucleus, but across a
                    // coda/semivowel tail the nucleus has grown (uo → uô → uô…
                    // / a → â → âu) and only the live form keeps that growth.
                    out.nucleus.setTo(unfoldToBase(out.nucleus))
                    unparkNucleus(out)
                    if (out.coda.isNotEmpty()) {
                        out.rawSuffix.append(c)
                        ctx.open = false
                    } else {
                        out.nucleus.append(c)
                    }
                }
                ctx.fold.clear()
                ctx.repackNucleus(out)
                ctx.justUntoggled = true
                return
            }
            // The fold anchor must remember the DISPLAY the user was looking at
            // before the fold fired, so a parked alias is captured as "ưo" even
            // though the buffer holds the resolved "ươ".
            nucleusDisplayScratch.clear()
            out.appendNucleusDisplay(nucleusDisplayScratch)
            val foldIdx = applyFoldRules(c, ctx.nucKey, pos, raw, out)
            if (foldIdx >= 0) {
                ctx.fold.set(cLow, pos, plainNucleus = nucleusDisplayScratch)
                ctx.repackNucleus(out)
                ctx.justUntoggled = false
                return
            }
        }
        if (ctx.open && out.nucleus.isNotEmpty() && cLow != 'w') {
            val combo = RimeMap.combineNucleus(out.nucleus, c)
            if (combo != null) {
                out.nucleus.setTo(combo)
                unparkNucleus(out)
                ctx.repackNucleus(out)
                return
            }
        }
        if (ctx.open) {
            when (extendNucleus(c, out, ctx, resolveAliasFirst = false)) {
                NucleusExtend.EXTENDED -> {
                    ctx.fold.clear()
                    applyPendingTone(out, ctx)
                    return
                }
                NucleusExtend.DISPLAY_REJECTED -> {
                    lockLiteral(out, ctx, c)
                    return
                }
                NucleusExtend.REJECTED -> Unit
            }
        }
        ctx.justUntoggled = false
        lockLiteral(out, ctx, c)
    }

    /** Plain vowel → start a nucleus or extend it; false falls through to literal. */
    private fun tryPlainVowel(c: Char, out: SyllableState, ctx: ScanCtx): Boolean {
        if (out.nucleus.isEmpty()) {
            if (out.onset.isNotEmpty()) {
                if (!RimeMap.isSyllableDisplayPrefixValid(out.onset, c)) {
                    return false
                }
            }
            out.nucleus.setTo(c)
            unparkNucleus(out)
            ctx.repackNucleus(out)
            ctx.justUntoggled = false
            applyPendingTone(out, ctx)
            return true
        }
        return extendNucleus(c, out, ctx, resolveAliasFirst = true) == NucleusExtend.EXTENDED
    }

    /** Outcome of appending a key to the nucleus — see [extendNucleus]. */
    private enum class NucleusExtend {
        /** The rime refused the key; the caller decides the literal fallback. */
        REJECTED,

        /** The nucleus grew; the caller applies the fold/tone side effects. */
        EXTENDED,

        /**
         * The rime accepted the key but the result cannot be displayed after the
         * onset.  Distinct from [REJECTED] because the caller must close the
         * syllable while leaving [ScanCtx.justUntoggled] alone.
         */
        DISPLAY_REJECTED
    }

    /**
     * Append [c] to the nucleus when the rime still accepts it — the step shared
     * by a modifier key ([handleModifierKey]) and a plain vowel
     * ([tryPlainVowel]).
     *
     * [resolveAliasFirst] is the one real difference between the two callers:
     * a plain vowel unparks a parked nucleus before appending, because a typed
     * vowel is a statement of intent; a modifier key keeps the nucleus parked.
     * Each call site keeps its own side effects ([ctx.fold], [applyPendingTone])
     * so those stay visible at the call site rather than hiding behind a flag.
     */
    private fun extendNucleus(
        c: Char,
        out: SyllableState,
        ctx: ScanCtx,
        resolveAliasFirst: Boolean
    ): NucleusExtend {
        if (out.coda.isNotEmpty()) return NucleusExtend.REJECTED
        val candidateKey = RimeMap.extendKeySingle(ctx.nucKey, c)
        if (!RimeMap.isValidPrefix(candidateKey)) return NucleusExtend.REJECTED
        if (out.nucleus.isEmpty() && out.onset.isNotEmpty() &&
            !RimeMap.isSyllableDisplayPrefixValid(out.onset, c)) {
            return NucleusExtend.DISPLAY_REJECTED
        }
        if (resolveAliasFirst) unparkNucleus(out)
        out.nucleus.append(c)
        // The append can land on a parked alias ("ư" + 'o' -> "ưo"): resolve it
        // in place so the buffer keeps holding a real rime, and let the flag
        // carry the parked spelling.  Appending always invalidates any earlier
        // parked state, so this assigns rather than ORs.
        out.parked = RimeMap.resolveAliasInto(out.nucleus, out.nucleus)
        ctx.repackNucleus(out)
        return NucleusExtend.EXTENDED
    }

    /**
     * Commit to the parked nucleus.  The buffer already holds the resolved rime,
     * so this only drops the flag — which is also what keeps the
     * parked ⟹ no coda, no tone invariant true for the paths that add one.
     */
    private fun unparkNucleus(out: SyllableState) {
        out.parked = false
    }

    /**
     * Consonant → coda via the flat map.  The fold keys that follow (e.g. the
     * double-a in tuana) are folded by the later modifier pass, so no lookahead
     * is needed here: the rime keys are resolved in gõ order (tuana → tuân).
     * Always consumes the key: a rejected consonant becomes literal text.
     */
    private fun tryCoda(c: Char, out: SyllableState, ctx: ScanCtx): Boolean {
        if (out.coda.length >= 2) {
            lockLiteral(out, ctx, c)
            return true
        }
        val key = RimeMap.extendKeySingle(ctx.rimeKey, c)
        if (!RimeMap.isValidPrefixWithTone(key, out.tone.index)) {
            lockLiteral(out, ctx, c)
            return true
        }
        unparkNucleus(out)
        out.coda.append(c)
        ctx.rimeKey = key
        return true
    }

    /**
     * Apply the Telex fold for [c] by reading the fold-target data baked into
     * [RimeMap] for the current nucleus — no per-rule if/else, no hardcoded
     * vowel-pair comparisons.  Returns the nucleus index where the fold landed
     * (untoggle anchor), or -1 when no fold applies.
     */
    private fun applyFoldRules(c: Char, nucKey: Int, rawPos: Int, raw: CharSequence, out: SyllableState): Int {
        // OwnedBuffer is a CharSequence: the fold overloads below read it in
        // place, so no snapshot String is allocated on the fold path.
        val nuc = out.nucleus
        // A parked alias folds on the DISPLAY the user typed ("ưo" w→ơ → "ươ"),
        // which is its own table row — foldW("ươ") is 0, so the resolved rime's
        // row would refuse the very fold that defines the alias.  Folding the
        // display keeps that row authoritative instead of relying on the two
        // forms agreeing everywhere except the folded index.
        val nucForFold = if (out.parked) {
            foldNucScratch.clear()
            out.appendNucleusDisplay(foldNucScratch)
            foldNucScratch
        } else {
            nuc
        }
        val slot = if (out.parked) RimeMap.foldSlot(RimeMap.parkedRimeKey(nuc)) else RimeMap.foldSlot(nucKey)
        if (slot < 0) return -1
        val primary = RimeMap.foldPrimaryAtSlot(slot, c)
        if (primary == 0) return -1
        val alt = if (c.lowercaseChar() == 'w') RimeMap.foldWAlt(slot) else 0

        if (alt == 0) {
            val newNuc = RimeMap.applyFold(nucForFold, primary)
            var ok = isValidRime(newNuc, out.coda)
            if (!ok && RimeMap.foldWPrimaryLookahead(slot)) {
                val tail = predictConsonantTail(raw, rawPos + 1)
                if (tail.isNotEmpty()) ok = isValidRime(newNuc, out.coda, tail)
            }
            if (!ok) return -1
            out.nucleus.setTo(newNuc)
            unparkNucleus(out)
            return RimeMap.foldPos(primary)
        }

        val tail = predictConsonantTail(raw, rawPos + 1)
        val primNuc = RimeMap.applyFold(nucForFold, primary)
        val altNuc = RimeMap.applyFold(nucForFold, alt)

        var chosen = pickWVariant(out.coda, tail, primNuc, altNuc, raw, rawPos, out)
        if (chosen == null && tail.isNotEmpty()) {
            // Shrink the lookahead tail so a real coda prefix (the "n" of "nh"
            // in "uownh") still licenses the fold; the rest stays literal
            // ("ươn" + "h"), like any invalid continuation.
            var k = tail.length - 1
            while (chosen == null && k >= 0) {
                chosen = pickWVariant(out.coda, tail.subSequence(0, k), primNuc, altNuc, raw, rawPos, out)
                k--
            }
        }
        if (chosen == null) return -1
        out.nucleus.setTo(chosen)
        unparkNucleus(out)
        return RimeMap.foldPos(primary)
    }

    /**
     * Pick the w-fold variant for a dual-variant (uo/uô) compound: when exactly
     * one folded form is a valid rime for [coda] take it; when both are valid use
     * the predicted vowel tail to break the tie; otherwise prefer the uo-family
     * rule (open ươ without a coda, closed uơ with one).
     */
    private fun pickWVariant(
        coda: CharSequence,
        tail: CharSequence,
        primNuc: String,
        altNuc: String,
        raw: CharSequence,
        rawPos: Int,
        out: SyllableState
    ): String? {
        val pv = isValidRime(primNuc, coda, tail)
        val av = isValidRime(altNuc, coda, tail)
        if (pv != av) return if (pv) primNuc else altNuc
        if (!pv) return null
        val vt = predictVowelTail(raw, rawPos + 1)
        if (vt.isNotEmpty()) {
            val primKey = RimeMap.keyCat(primNuc, primNuc.length, vt, vt.length)
            val altKey = RimeMap.keyCat(altNuc, altNuc.length, vt, vt.length)
            val pe = RimeMap.isValidPrefix(RimeMap.extendKey(primKey, out.coda, 0, out.coda.length))
            val ae = RimeMap.isValidPrefix(RimeMap.extendKey(altKey, out.coda, 0, out.coda.length))
            if (pe != ae) return if (pe) primNuc else altNuc
        }
        val openUoOk = out.onset.isEmpty() || OnsetMap.allowsOpenUo(out.onset)
        return if (out.coda.isNotEmpty() || !openUoOk) primNuc else altNuc
    }

    private fun isValidRime(nucleus: String, coda: CharSequence): Boolean {
        return RimeMap.isValidPrefix(RimeMap.keyCat(nucleus, nucleus.length, coda, coda.length))
    }

    /**
     * Two-part coda check — bit-identical to [isValidRime] over the
     * concatenated coda+tail, because [RimeMap.extendKey] continues packing
     * from the coda key (an empty tail is a no-op). Avoids building the
     * intermediate concatenated String on the fold path.
     */
    private fun isValidRime(nucleus: String, coda: CharSequence, tail: CharSequence): Boolean {
        val key = RimeMap.extendKey(
            RimeMap.keyCat(nucleus, nucleus.length, coda, coda.length), tail, 0, tail.length
        )
        return RimeMap.isValidPrefix(key)
    }

    /** True while a preedit session has a non-empty raw buffer. */
    fun isComposing(): Boolean = processRaw.isNotEmpty()

    /** Read-only view of the composing raw keystrokes (caret mapping helpers). */
    fun composingRaw(): CharSequence = processRaw

    fun composingRawLength(): Int = processRaw.length

    /**
     * Replaces the composing raw buffer (adoption / display-level edits).
     * When [composeAsVietnamese] is false the text is kept verbatim (word-edit literal
     * lock); otherwise it is resegmented through the Telex kernel.
     */
    /** Refresh composing state after a raw-buffer mutation (resegment if Vietnamese). */
    private fun refreshProcessState() {
        if (composeAsVietnamese) {
            resegment(processRaw, processState)
        } else {
            processState.reset()
            processState.rawSuffix.setTo(processRaw)
        }
    }

    fun setComposingRaw(raw: CharSequence) {
        processRaw.setLength(0)
        processRaw.append(raw)
        refreshProcessState()
    }

    /** Inserts one Telex key at [index] of the composing raw and resegments. */
    fun insertComposingKey(index: Int, key: Char) {
        if (index >= processRaw.length) processRaw.append(key) else processRaw.insert(index, key)
        refreshProcessState()
    }

    fun processKey(key: Char): CompositionResult {
        if (isBoundaryKey(key)) {
            val commitText = processState.toDisplayString(options.oldTonePlacement)
            processRaw.clear()
            processState.reset()
            composeAsVietnamese = true
            return CompositionResult.CommitAndStartNew(commitText, key)
        }
        insertComposingKey(processRaw.length, key)
        return CompositionResult.Update(processState.toDisplayString(options.oldTonePlacement))
    }

    fun backspace(): String {
        if (processRaw.isEmpty()) return ""
        val display = processState.toDisplayString(options.oldTonePlacement)
        val start = GraphemeEditor.previousBoundary(display, display.length)
        if (start <= 0) {
            processRaw.clear(); processState.reset()
            return ""
        }
        val survivor = display.substring(0, start)
        val canonical = adoptRoundTrip(survivor)
        composeAsVietnamese = canonical != null
        setComposingRaw(canonical ?: survivor)
        return processState.toDisplayString(options.oldTonePlacement)
    }

    /** Compile raw into an existing buffer — avoids allocation per call. */
    fun compileRawInto(raw: CharSequence, vietnamese: Boolean, out: OwnedBuffer, maxLen: Int = raw.length) {
        out.clear()
        val rawLen = maxLen.coerceAtMost(raw.length)
        if (rawLen == 0) return
        if (!vietnamese || !vietnameseModeEnabled) { out.append(raw, 0, rawLen); return }

        var i = 0
        while (i < rawLen) {
            val c = raw[i]
            if (isBoundaryKey(c)) {
                out.append(c)
                replayState.reset()
                i++
                continue
            }
            val start = i
            while (i < rawLen && !isBoundaryKey(raw[i])) i++
            val syllable = raw.subSequence(start, i)
            resegment(syllable, replayState)
            replayState.toDisplayBuffer(syllableRenderBuf, options.oldTonePlacement)
            out.append(syllableRenderBuf)
        }
        replayState.reset()
    }

    /** Single source for syllable-breaking characters, shared with the UI. */
    private fun isBoundaryKey(c: Char): Boolean =
        BoundaryClassifier.isBoundaryChar(c)

    fun adoptWord(word: String): AdoptResult? {
        if (word.isEmpty()) return null
        val nfcWord = VietnameseUnicode.normalizeNfc(word)

        var detectedTone = Tone.NONE
        val untonedChars = StringBuilder()
        for (c in nfcWord) {
            val t = VietnameseUnicode.toneOf(c)
            if (t != Tone.NONE && detectedTone == Tone.NONE) detectedTone = t
            untonedChars.append(VietnameseUnicode.stripTone(c))
        }
        val baseWord = untonedChars.toString()
        val baseLower = baseWord.lowercase()

        val onsetLen = OnsetMap.longestOnsetPrefix(baseLower)
        var onset = if (onsetLen > 0) baseWord.substring(0, onsetLen) else ""
        var remainingAfterOnset = baseWord.substring(onsetLen)

        val nucleusEnd = scanNucleusEnd(remainingAfterOnset)
        var nucleus = remainingAfterOnset.substring(0, nucleusEnd)
        var remainingAfterNucleus = remainingAfterOnset.substring(nucleusEnd)
        var remLower = remainingAfterNucleus.lowercase()

        if (nucleus.isEmpty() && OnsetMap.isGiOnset(onset)) {
            val shorterOnset = baseWord.substring(0, onset.length - 1)
            if (OnsetMap.isCompleteOnset(shorterOnset.lowercase(), 0, shorterOnset.length)) {
                onset = shorterOnset
                remainingAfterOnset = baseWord.substring(onset.length)
                val nucEnd2 = scanNucleusEnd(remainingAfterOnset)
                nucleus = remainingAfterOnset.substring(0, nucEnd2)
                remainingAfterNucleus = remainingAfterOnset.substring(nucEnd2)
                remLower = remainingAfterNucleus.lowercase()
            }
        }

        var coda = ""
        var rawSuffix = ""
        if (nucleus.isNotEmpty()) {
            var matchedCoda = false
            for (cand in RimeMap.CODAS) {
                if (remLower.startsWith(cand)) {
                    val candidateRime = nucleus.lowercase() + cand
                    val candidateRimeKey = RimeMap.rimeKey(candidateRime.lowercase())
                    if (RimeMap.isRimeKeyValidForTone(candidateRimeKey, detectedTone)) {
                        coda = remainingAfterNucleus.substring(0, cand.length)
                        rawSuffix = remainingAfterNucleus.substring(cand.length)
                        matchedCoda = true; break
                    }
                }
            }
            if (!matchedCoda) rawSuffix = remainingAfterNucleus
        } else {
            rawSuffix = remainingAfterNucleus
        }

        // Parked "ưo" validates via its "uo" shape; rawKeyForNucleus("ưo") = "uwo"
        // replays identically (round-trip gate below, so this only adds wins).
        val rimeNucleus = if (nucleus.equals("ưo", ignoreCase = true)) "uo" else nucleus
        val rimeKey = rimeNucleus.lowercase() + coda.lowercase()
        val rimeKeyFull = if (rimeKey.isEmpty()) 0 else RimeMap.rimeKey(rimeKey)
        val hasValidRime = nucleus.isNotEmpty() && RimeMap.isValidPrefix(rimeKeyFull)
        val validTone = if (hasValidRime) detectedTone else Tone.NONE
        val validSuffix = if (hasValidRime) rawSuffix else (if (detectedTone != Tone.NONE) word.substring(onset.length) else rawSuffix)

        val isValidRimeOrPrefix = if (nucleus.isEmpty()) {
            onset.isNotEmpty() && coda.isEmpty()
        } else {
            RimeMap.isRimeKeyValidForTone(rimeKeyFull, validTone)
        }
        val isValid = validSuffix.isEmpty() && isValidRimeOrPrefix

        val canonicalRaw = if (isValid) {
            canonicalRawOf(canonicalScratch, onset, nucleus, nucleusToRaw(nucleus), coda, null, validTone, word)
        } else { word }

        val canonicalFoldLast = if (isValid) {
            canonicalFoldLastRaw(plainScratch, canonicalScratch, onset, nucleus, coda, validTone, word)
        } else null

        return AdoptResult(isValid, onset.length, canonicalRaw, canonicalFoldLast)
    }

    /**
     * Canonical Telex raw for [adopt] when the word round-trips exactly through
     * the Telex kernel; null otherwise.  Shared by every adopt path (composer
     * backspace, IME display edits, prefix adoption, mid-word resume) so the
     * "adopt iff replay == display" decision lives in exactly one place.
     */
    fun canonicalRawIfRoundTrips(adopt: AdoptResult?, display: String): String? {
        if (adopt == null || !adopt.isValid) return null
        adopt.canonicalFoldLast?.let { foldedLast ->
            if (process(foldedLast) == display) return foldedLast
        }
        val canonical = adopt.canonicalRaw
        return if (process(canonical) == display) canonical else null
    }

    /** Scans the maximal base-vowel run at the start of [remainingAfterOnset]:
     *  returns (nucleus, remainingAfterNucleus). */
    private fun scanNucleusEnd(remainingAfterOnset: String): Int {
        var i = 0
        while (i < remainingAfterOnset.length && RimeMap.isBaseVowel(remainingAfterOnset[i])) {
            i++
        }
        return i
    }

    /** Canonical raw onset with `đ` folded by casing (Đ→DD, Đx→Dd, else dd). */

    /**
     * [adoptWord] + round-trip gate in one call — null when not adoptable.
     * (Refactor note: composing-state refresh, nucleus scan, and `đ` onset
     * folding are each shared by one helper — see scanNucleusEnd and
     * canonicalOnsetFold above.)
     */
    fun adoptRoundTrip(display: String): String? =
        canonicalRawIfRoundTrips(adoptWord(display), display)

    fun process(raw: String): String {
        if (raw.isEmpty()) return ""
        compileRawInto(raw, true, stringOut)
        return stringOut.toStringVal()
    }

    /** Apply deferred tone (from handleToneKey) to the current nucleus. */
    private fun applyPendingTone(out: SyllableState, ctx: ScanCtx) {
        if (ctx.pendingTone != Tone.NONE) {
            val rk = RimeMap.keyCat(out.nucleus, out.nucleus.length, out.coda, out.coda.length)
            if (RimeMap.isRimeKeyValidForTone(rk, ctx.pendingTone)) {
                out.tone = ctx.pendingTone
                ctx.lastToneKey = ctx.pendingToneKey
            }
            ctx.pendingTone = Tone.NONE
            ctx.pendingToneKey = '\u0000'
        }
    }

    companion object {
        /** scanBody dispatch categories — bitmask: a char can match several. */
        private const val CAT_TONE = 1
        private const val CAT_FOLD = 2
        private const val CAT_VOWEL = 4
        private const val CAT_CONSONANT = 8

        /**
         * One-read char classifier for the [scanBody] hot loop.  Bitmask of
         * tone/fold/vowel/consonant categories keyed by char code; dispatch
         * precedence is preserved (tone > fold > vowel > consonant > literal).
         * The 512-entry range covers the Vietnamese base/shape vowels
         * (ă â ê ô ơ ư đ) that can reach the scan after an adopt-fail raw
         * buffer, so non-ASCII chars classify exactly like the old per-check
         * calls.
         */
        private val KEY_CAT = IntArray(512).also { cat ->
            for (c in RimeMap.TONE_KEYS) cat[c.code] = cat[c.code] or CAT_TONE
            for (c in RimeMap.VOWEL_MOD_KEYS) cat[c.code] = cat[c.code] or CAT_FOLD
            for (c in RimeMap.BASE_VOWELS) cat[c.code] = cat[c.code] or CAT_VOWEL
            for (c in 0 until 512) {
                if (OnsetMap.isConsonant(c.toChar())) cat[c] = cat[c] or CAT_CONSONANT
            }
        }

        private fun keyCat(c: Char): Int {
            val code = c.lowercaseChar().code
            return if (code in 0 until KEY_CAT.size) KEY_CAT[code] else 0
        }

        /**
         * onset + nucleusRaw + coda + [extraRaw] + tone key, then the caller's
         * casing applied — the canonical-raw layout shared by [adoptWord] and
         * [canonicalFoldLastRaw].  The tone key is upper-cased only when the
         * whole nucleus is upper-case, which is what keeps an all-caps syllable
         * (NHA → Nhà, "toà" → "TOÀ") from becoming half-lower.
         *
         * [extraRaw] is [canonicalFoldLastRaw]'s trailing fold key; [adoptWord]
         * has none.  [scratch] is the caller's reusable buffer: this lives in the
         * companion object, so the buffer must be owned by the composer instance
         * (thread-confined) rather than allocated per call.
         */
        private fun canonicalRawOf(
            scratch: OwnedBuffer,
            onset: String,
            nucleus: String,
            nucleusRaw: CharSequence,
            coda: String,
            extraRaw: Char?,
            tone: Tone,
            word: String
        ): String {
            scratch.clear()
            scratch.append(OnsetMap.rawKeyForOnset(onset))
            scratch.append(nucleusRaw)
            scratch.append(coda)
            if (extraRaw != null) scratch.append(extraRaw)
            val toneKey = tone.key
            if (toneKey != null) {
                val nucAllUpper = nucleus.isNotEmpty() && nucleus.all { it.isUpperCase() }
                scratch.append(if (nucAllUpper) toneKey.uppercaseChar() else toneKey)
            }
            return VietnameseUnicode.applyCasingFromRaw(scratch, word)
        }

        @JvmStatic
        fun isToneKey(c: Char): Boolean = RimeMap.isToneKey(c)

        @JvmStatic
        fun isVowelModifierKey(c: Char): Boolean = RimeMap.isFoldKey(c)

        fun nucleusToRaw(nucleus: String): String {
            if (nucleus.isEmpty()) return ""
            val raw = RimeMap.rawKeyForNucleus(nucleus)
            val allUpper = nucleus.all { it.isUpperCase() }
            val firstUpper = nucleus.isNotEmpty() && nucleus[0].isUpperCase()
            return when {
                allUpper -> raw.uppercase()
                firstUpper -> raw.replaceFirstChar { it.uppercase() }
                else -> raw
            }
        }

        /**
         * Fold-last canonical raw for a folded nucleus + coda: plain nucleus +
         * coda + the single Telex fold key + tone key (bân → "bana", uyên →
         * "uyene", xuất → "xuatas"). Only single-fold nuclei (one folded vowel)
         * qualify — multi-fold w-compounds (ươ/uơ) keep the fold-first spelling
         * through [nucleusToRaw]. Returns null when no reordering applies
         * (no coda, nothing folded, or a compound fold).
         */
        fun canonicalFoldLastRaw(
            plainScratch: OwnedBuffer, rawScratch: OwnedBuffer,
            onset: String, nucleus: String, coda: String,
            tone: Tone, word: String
        ): String? {
            if (nucleus.isEmpty() || coda.isEmpty()) return null
            plainScratch.clear()
            var foldKey: Char? = null
            var foldedCount = 0
            for (ch in nucleus) {
                val lc = ch.lowercaseChar()
                val pc = RimeMap.plainOf(lc)
                val fk = RimeMap.foldKeyFor(ch)
                if (pc != lc && fk != null) {
                    plainScratch.append(pc)
                    foldedCount++
                    if (foldKey == null) foldKey = fk
                } else {
                    plainScratch.append(ch)
                }
            }
            val fk = foldKey ?: return null
            if (foldedCount != 1) return null

            return canonicalRawOf(rawScratch, onset, nucleus, plainScratch, coda, fk, tone, word)
        }
    }

    var macroStore: MacroStore? = null
    private var macroPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var settingsPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun loadPreferences(context: Context) {
        withPrefs(context) {
            val config = AppPreferences.getEngineConfig()
            applyConfig(config)
            macroStore = MacroRepository(context).loadMacroStore()
            if (macroPrefsListener == null) {
                macroPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (AppPreferences.isMacroDataKey(key)) reloadMacroStore(context)
                }
                AppPreferences.registerMacroPrefsListener(macroPrefsListener!!)
            }
            if (settingsPrefsListener == null) {
                settingsPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                    loadPreferences(context)
                }
                AppPreferences.registerSettingsPrefsListener(settingsPrefsListener!!)
            }
        }
    }

    fun cleanup() {
        macroPrefsListener?.let { AppPreferences.unregisterMacroPrefsListener(it); macroPrefsListener = null }
        settingsPrefsListener?.let { AppPreferences.unregisterSettingsPrefsListener(it); settingsPrefsListener = null }
    }

    fun reloadMacroStore(context: Context) {
        withPrefs(context) {
            applyConfig(AppPreferences.getEngineConfig())
            macroStore = MacroRepository(context).loadMacroStore()
            reset()
        }
    }

    fun savePreferences(
        context: Context,
        macro: Boolean = options.macroEnabled,
        autoCap: Boolean = autoCapitalize,
        dirW: Boolean = options.directW,
        oldTone: Boolean = options.oldTonePlacement
    ) {
        withPrefs(context) {
            val config = EngineConfig(macroEnabled = macro,
                autoCapitalize = autoCap, directW = dirW, oldTonePlacement = oldTone)
            AppPreferences.setEngineConfig(config)
            applyConfig(config)
        }
    }

    /**
     * The three prefs entry points differ only in what they do once the store is
     * reachable, and each must survive an unreachable store the same way: keep
     * whatever state the engine already holds, which leaves a usable keyboard on
     * its defaults (an unreadable load), a usable macro set (a failed reload),
     * and the config the caller already applied (a failed write).
     */
    private fun withPrefs(context: Context, block: () -> Unit) {
        try {
            AppPreferences.init(context)
            block()
        } catch (e: Exception) {
            // Intentionally swallowed: the engine stays usable on current state.
        }
    }

    private fun applyConfig(config: EngineConfig) {
        options.macroEnabled = config.macroEnabled
        options.directW = config.directW
        options.oldTonePlacement = config.oldTonePlacement
        autoCapitalize = config.autoCapitalize
    }
}

