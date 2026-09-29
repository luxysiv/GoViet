package com.goviet.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Safety-net matrix for [VietnameseComposer] — the behavioural contract that must
 * keep holding through any future refactor (zero-alloc adoptWord, table merges,
 * O(n) caret mapping, ...).
 *
 * Covers: tone matrix (s/f/r/x/j) + untoggle, fold matrix (aa/aw/ee/oo/ow/uw/dd)
 * + untoggle, gi/qu/uo-uoi families, incremental processKey chains, grapheme
 * backspace chains, emoji/ZWJ/flag adoption failure + literal lock, and the
 * [GraphemeEditor] boundaries the backspace path depends on.
 *
 * (Caret mapping rawIndexOfDisplay/displayCursorIndex lives in
 * CaretMappingTest — it needs a controller instance, i.e. Robolectric.)
 */
class ComposerSafetyMatrixTest {

    private lateinit var engine: VietnameseComposer

    @Before
    fun setUp() {
        engine = VietnameseComposer()
        engine.vietnameseModeEnabled = true
    }

    private fun type(raw: String) {
        for (c in raw) engine.processKey(c)
    }

    // ── 1. Tone matrix: 5 tones × plain vowel ──────────────────────────────
    @Test
    fun toneMatrix_plainVowel() {
        assertEquals("á", engine.process("as"))
        assertEquals("à", engine.process("af"))
        assertEquals("ả", engine.process("ar"))
        assertEquals("ã", engine.process("ax"))
        assertEquals("ạ", engine.process("aj"))
    }

    @Test
    fun toneMatrix_withOnset() {
        assertEquals("má", engine.process("mas"))
        assertEquals("mà", engine.process("maf"))
        assertEquals("mả", engine.process("mar"))
        assertEquals("mã", engine.process("max"))
        assertEquals("mạ", engine.process("maj"))
        assertEquals("giá", engine.process("gisa"))
        assertEquals("già", engine.process("gifa"))
        assertEquals("giả", engine.process("gira"))
        assertEquals("giã", engine.process("gixa"))
        assertEquals("giạ", engine.process("gija"))
        assertEquals("chéch", engine.process("chechs"))
    }

    @Test
    fun toneUntoggle_doubleToneKeyIsLiteral() {
        assertEquals("as", engine.process("ass"))
        assertEquals("af", engine.process("aff"))
        assertEquals("ar", engine.process("arr"))
        assertEquals("ax", engine.process("axx"))
        assertEquals("aj", engine.process("ajj"))
    }

    @Test
    fun toneKeyWithoutVowelStaysLiteral() {
        assertEquals("dsa", engine.process("dsa"))
        assertEquals("tja", engine.process("tja"))
        assertEquals("qus", engine.process("qus"))
    }

    // ── 2. Fold matrix + untoggle ──────────────────────────────────────────
    @Test
    fun foldMatrix() {
        assertEquals("â", engine.process("aa"))
        assertEquals("ă", engine.process("aw"))
        assertEquals("ê", engine.process("ee"))
        assertEquals("ô", engine.process("oo"))
        assertEquals("ơ", engine.process("ow"))
        assertEquals("ư", engine.process("uw"))
        assertEquals("đ", engine.process("dd"))
        assertEquals("đa", engine.process("dad"))
    }

    @Test
    fun foldUntoggle_tripleKeyIsLiteral() {
        assertEquals("aa", engine.process("aaa"))
        assertEquals("aw", engine.process("aww"))
        assertEquals("ee", engine.process("eee"))
        assertEquals("oo", engine.process("ooo"))
        assertEquals("ow", engine.process("oww"))
        assertEquals("uw", engine.process("uww"))
        assertEquals("dd", engine.process("ddd"))
    }

    // ── 3. gi / qu / uo families ───────────────────────────────────────────
    @Test
    fun giOnsetMatrix() {
        // Standalone w after an onset folds to ư.
        assertEquals("giư", engine.process("giw"))
        assertEquals("sư", engine.process("sw"))
        assertEquals("tư", engine.process("tw"))
        // gi + tone + vowel extends the rime instead of locking.
        assertEquals("giá", engine.process("gisa"))
        // Second i after gi cannot start a new nucleus → literal lock.
        assertEquals("gii", engine.process("gii"))
        assertEquals("giie", engine.process("giie"))
    }

    @Test
    fun quClusterMatrix() {
        // qu is a consonant cluster: its u is never a nucleus.
        assertEquals("qusa", engine.process("qusa"))
        assertEquals("quu", engine.process("quu"))
        // Tone after the rime vowel still transforms.
        assertEquals("quá", engine.process("quas"))
    }

    @Test
    fun uoFamilyMatrix() {
        assertEquals("uơ", engine.process("uow"))
        assertEquals("thuơ", engine.process("thuow"))
        assertEquals("thươn", engine.process("thuown"))
        type("chuyeenr")
        assertEquals("chuyển", engine.toDisplayString())
    }

    @Test
    fun foldRetypeContracts() {
        // Commit "luyên", retype e → untoggle to literal "luyene".
        type("luyene")
        assertEquals("luyên", engine.toDisplayString())
        assertEquals("luyene", engine.adoptRoundTrip("luyên"))
        engine.composeAsVietnamese = true
        engine.setComposingRaw("luyene")
        engine.processKey('e')
        assertEquals("luyene", engine.toDisplayString())
        // Same contract for uâ: commit "luân" + a → "luana".
        engine.reset()
        type("luana")
        assertEquals("luân", engine.toDisplayString())
        assertEquals("luana", engine.adoptRoundTrip("luân"))
    }

    // ── 4. Incremental processKey chains ───────────────────────────────────
    @Test
    fun incrementalTyping_buildsDisplayStepByStep() {
        engine.reset()
        assertEquals("d", engine.processKey('d').text.toString())
        assertEquals("đ", engine.processKey('d').text.toString())
        assertEquals("đa", engine.processKey('a').text.toString())
        assertEquals("đan", engine.processKey('n').text.toString())
        assertEquals("đang", engine.processKey('g').text.toString())
    }

    @Test
    fun boundaryKey_commitsAndStartsNew() {
        type("as")
        assertEquals("á", engine.toDisplayString())
        val result = engine.processKey(' ')
        assertTrue(result is VietnameseComposer.CompositionResult.CommitAndStartNew)
        assertEquals("á", (result as VietnameseComposer.CompositionResult.CommitAndStartNew).commitText)
        assertFalse(engine.isComposing())
    }

    @Test
    fun statelessProcess_doesNotTouchInteractiveState() {
        type("ddang")
        assertEquals("đang", engine.toDisplayString())
        assertEquals("tiếng việt", engine.process("tiếng việt"))
        assertEquals("đang", engine.toDisplayString())
    }

    // ── 5. Grapheme backspace chains ───────────────────────────────────────
    @Test
    fun backspaceChain_chuyen() {
        engine.reset()
        type("chuyeenr")
        assertEquals("chuyển", engine.toDisplayString())
        assertEquals("chuyể", engine.backspace())
        assertEquals("chuy", engine.backspace())
        assertEquals("chu", engine.backspace())
        assertEquals("ch", engine.backspace())
        assertEquals("c", engine.backspace())
        assertEquals("", engine.backspace())
    }

    @Test
    fun backspaceChain_thayVisualReduction() {
        // "thayas" types "thấy" ([t][h][ấ][y]); backspace must walk the visual
        // chain thấy -> thấ -> th -> t -> "" through the real engine path
        // (BackspaceReplayTest only covers the grapheme helper).
        engine.reset()
        type("thayas")
        assertEquals("thấy", engine.toDisplayString())
        assertEquals("thấ", engine.backspace())
        assertEquals("th", engine.backspace())
        assertEquals("t", engine.backspace())
        assertEquals("", engine.backspace())
    }

    @Test
    fun backspaceForeignWord_locksLiteral() {
        engine.reset()
        type("deepseel")
        assertEquals("dépeel", engine.toDisplayString())
        // Survivor "dépee" cannot round-trip → literal lock, never re-Telexed.
        assertEquals("dépee", engine.backspace())
        assertFalse(engine.composeAsVietnamese)
        assertEquals("dépeek", engine.processKey('k').text.toString())
    }

    // ── 6. Emoji / ZWJ / flag: adoption fails, typing stays literal ────────
    @Test
    fun adoptRoundTrip_rejectsNonVietnamese() {
        assertTrue(engine.adoptRoundTrip("warm") == null)
        assertTrue(engine.adoptRoundTrip("confirm") == null)
        assertTrue(engine.adoptRoundTrip("😀") == null)
        assertTrue(engine.adoptRoundTrip("👨‍👩‍👧‍👦") == null)
        assertTrue(engine.adoptRoundTrip("🇻🇳") == null)
        // ...while real Vietnamese still round-trips.
        assertEquals("toan", engine.adoptRoundTrip("toan"))
        assertEquals("toas", engine.adoptRoundTrip("toá"))
    }

    @Test
    fun graphemeEditor_emojiAndZwjBoundaries() {
        val family = "👨‍👩‍👧‍👦"
        assertEquals(0, GraphemeEditor.previousBoundary(family, family.length))
        assertEquals(family.length, GraphemeEditor.nextBoundary(family, 0))
        assertEquals(0, GraphemeEditor.previousBoundary("🇻🇳", 4))
        assertEquals(1, GraphemeEditor.previousBoundary("a😀", 3))
        assertEquals(1, GraphemeEditor.nextBoundary("a😀", 0))
        assertEquals(3, GraphemeEditor.nextBoundary("a😀", 1))
        val (text, cursor) = GraphemeEditor.deleteBackward("thấy", 4)
        assertEquals("thấ", text)
        assertEquals(3, cursor)
    }

    @Test
    fun backspaceEmoji_locksLiteralAndKeepsSurvivor() {
        engine.reset()
        engine.setComposingRaw("😀😃")
        assertEquals("😀😃", engine.toDisplayString())
        // Exactly one grapheme (one emoji) is removed; the survivor cannot be
        // re-adopted as Telex, so the buffer locks literal.
        assertEquals("😀", engine.backspace())
        assertFalse(engine.composeAsVietnamese)
        assertEquals("😀x", engine.processKey('x').text.toString())
    }
}
