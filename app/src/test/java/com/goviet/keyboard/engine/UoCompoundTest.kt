package com.goviet.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the uo/uơ/ươ compound family: dual w-fold + "nh" coda
 * (uownh -> ươnh), the parked "ưo" display (duwoj -> dượ, uwow -> ươ), and
 * đươ retone re-adoption (dduwow).
 */
class UoCompoundTest {

    private lateinit var engine: VietnameseComposer

    @Before
    fun setUp() {
        engine = VietnameseComposer()
        engine.vietnameseModeEnabled = true
    }

    private fun type(raw: String) {
        for (c in raw) engine.processKey(c)
    }

    /** Simulate the IME display-level re-adoption (see BugReproductionTest). */
    private fun adoptBack(survivor: String): String {
        val canonical = engine.adoptRoundTrip(survivor)
        assertNotNull("'$survivor' must be re-adoptable", canonical)
        engine.composeAsVietnamese = true
        engine.setComposingRaw(canonical!!)
        return canonical
    }

    // ── Bug 1: nh-coda resolves the dual fold to ươ ──────────────────────
    @Test
    fun uownhs_toneAfterBrokenStaysLiteral() {
        // "h" locks the syllable, so the later tone key stays literal too
        // (mirror of "deepseel" where the tone lands before locking).
        assertEquals("ươnhs", engine.process("uownhs"))
    }

    @Test
    fun uownh_incrementalTyping() {
        engine.reset()
        type("uownh")
        assertEquals("ươnh", engine.toDisplayString())
    }

    @Test
    fun uowch_splitsDigraphToValidCoda() {
        // "c" licenses the fold, "h" stays literal (user-confirmed).
        assertEquals("ươch", engine.process("uowch"))
    }

    @Test
    fun uoFamily_regressions() {
        // Open reading stays uơ.
        assertEquals("uơ", engine.process("uow"))
        // n-coda still resolves to ươ.
        assertEquals("ươn", engine.process("uown"))
        // ng-coda still resolves to ươ.
        assertEquals("ương", engine.process("uowng"))
        // Genuinely invalid tail keeps the open reading (k stays literal).
        assertEquals("uơk", engine.process("uowk"))
        // Plain uơ display form keeps adopting.
        assertEquals("thuow", engine.adoptRoundTrip("thuơ"))
        assertEquals("thuơ", engine.process("thuow"))
    }

    // ── Bug 2: "ưo" pending form resolves; đươ re-adopts as "uwow" ───────
    @Test
    fun pendingUo_displayStaysParked() {
        assertEquals("dưo", engine.process("duwo"))
    }

    @Test
    fun pendingUo_toneResolves() {
        assertEquals("dượ", engine.process("duwoj"))
        assertEquals("đượ", engine.process("dduwoj"))
    }

    @Test
    fun parkedUo_forwardSanity() {
        // dd -> đ onset; full canon "uwow" replays to ươ.
        assertEquals("đươ", engine.process("dduwow"))
    }

    @Test
    fun parkedUo_adoptsToCanonical() {
        assertEquals("dduwow", engine.adoptRoundTrip("đươ"))
    }

    @Test
    fun parkedUo_retoneAfterCommit() {
        // "đươ" + space + backspace(space) + "j" -> "đượ".
        type("dduwow")
        assertEquals("đươ", engine.toDisplayString())
        adoptBack("đươ")
        assertEquals("dduwow", engine.composingRaw().toString())
        engine.processKey('j')
        assertEquals("đượ", engine.toDisplayString())
    }

    // ── alias mechanism edges (park stays until a follower resolves) ──────
    @Test
    fun aliasParked_oFollowupStaysLiteral() {
        // A second "o" folds against the alias slot ("ưô" dead) then literal.
        assertEquals("ưoo", engine.process("uwoo"))
    }

    @Test
    fun aliasParked_vowelResolvesForward() {
        // The parked form feeds the whole ươ family.
        assertEquals("tươi", engine.process("tuwoi"))
        assertEquals("ươc", engine.process("uwoc"))
    }

    @Test
    fun aliasParked_toneKeyResolvesAlias() {
        // 'x' is ngã — a tone key resolves the parked alias (like duwoj).
        assertEquals("dưỡ", engine.process("duwox"))
    }

    @Test
    fun aliasParked_invalidFollowerStaysLiteral() {
        // An invalid follower keeps the parked form literal.
        assertEquals("dưok", engine.process("duwok"))
    }

    @Test
    fun aliasParked_uppercasePivot() {
        // Uppercase pivot W through the alias fold slot, casing preserved.
        assertEquals("Ươ", engine.process("WoW"))
    }

    @Test
    fun foldWAlt_isCaseInsensitive() {
        // The 'w' alternative fold must be reachable from an uppercase W too:
        // foldPrimaryAtSlot() lowercases internally, so gating the alt variant
        // on the raw char left 'W' on the primary-only path and lost the
        // uo/uô tie-break, giving "ƯƠ" where "uow" gives "uơ".
        //
        // The alt variant only decides the outcome when the coda is empty —
        // pickWVariant() returns the primary outright once a coda is present —
        // so these raws deliberately have none.
        for (raw in listOf("uow", "uowr", "uows", "uowl", "thuow", "huow")) {
            assertEquals(engine.process(raw).uppercase(), engine.process(raw.uppercase()))
        }
    }

    @Test
    fun closingSemivowelsDeriveRawLikeConsonantCodas() {
        // Closing semivowels type literally like codas: ươn -> uwown,
        // ươi -> uwowi, ươu -> uwowu.
        assertEquals("dduwown", engine.adoptRoundTrip("đươn"))
        assertEquals("tuwowi", engine.adoptRoundTrip("tươi"))
        assertEquals("ruwowuj", engine.adoptRoundTrip("rượu"))
    }
}
