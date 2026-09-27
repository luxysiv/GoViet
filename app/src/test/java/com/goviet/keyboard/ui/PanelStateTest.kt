package com.goviet.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel state machine, as a type rather than a set of strings.
 *
 * The bug this pins: "SYMBOLS" and "SYMBOL_PICKER" were both legal values of
 * one string, so a panel could be handed the other's configuration and render
 * the wrong grid with no error anywhere. These tests are cheap; the value is
 * that a new panel now has to be added in three places instead of one, and the
 * compiler points at the places it forgot.
 */
class PanelStateTest {

    @Test
    fun `every panel is a distinct object`() {
        val all = listOf(
            PanelState.Letters,
            PanelState.SymbolPage,
            PanelState.SymbolPicker,
            PanelState.Emoji,
            PanelState.Tpad,
            PanelState.Clipboard,
            PanelState.EditPad,
            PanelState.Settings
        )
        // A copy-paste that gave two names the same object would make the set
        // collapse, and the two panels would then be indistinguishable.
        assertEquals(all.size, all.toSet().size)
        all.forEachIndexed { i, a ->
            all.drop(i + 1).forEach { b ->
                assertNotSame("two panels share an object: $a / $b", a, b)
            }
        }
    }

    @Test
    fun `the letter grid has two panels, and they are different panels`() {
        // The whole point of the split: a symbol page in the letter grid is not
        // the full-screen symbol picker.
        assertNotSame(PanelState.SymbolPage as Any, PanelState.SymbolPicker as Any)
        assertFalse(PanelState.SymbolPage.isDrawerPanel)
        assertFalse(PanelState.SymbolPicker.isDrawerPanel)
    }

    @Test
    fun `isDrawerPanel covers the four panels reached from the drawer`() {
        assertTrue(PanelState.Settings.isDrawerPanel)
        assertTrue(PanelState.Clipboard.isDrawerPanel)
        assertTrue(PanelState.EditPad.isDrawerPanel)
        assertTrue(PanelState.Tpad.isDrawerPanel)
    }

    @Test
    fun `isDrawerPanel is false for the panels that show the toolbar toggle`() {
        assertFalse(PanelState.Letters.isDrawerPanel)
        assertFalse(PanelState.SymbolPage.isDrawerPanel)
        assertFalse(PanelState.SymbolPicker.isDrawerPanel)
        assertFalse(PanelState.Emoji.isDrawerPanel)
    }

    @Test
    fun `the letter grid page walks between the two symbol pages`() {
        assertSame(LetterPage.SYMBOLS_2, LetterPage.SYMBOLS_1.other())
        assertSame(LetterPage.SYMBOLS_1, LetterPage.SYMBOLS_2.other())
        // From the letter page, the "other" page is the first symbol page, not
        // back to itself: the switch key on letters is what opens "123".
        assertSame(LetterPage.SYMBOLS_1, LetterPage.LETTERS.other())
    }

    @Test
    fun `the two symbol pages walk back to each other`() {
        // Not a round trip from LETTERS: other() sends the letter page to the
        // first symbol page, and from there it alternates. So the cycle that
        // has to close is the two symbol pages alone.
        var page = LetterPage.SYMBOLS_1
        repeat(2) { page = page.other() }
        assertSame(LetterPage.SYMBOLS_1, page)

        var other = LetterPage.SYMBOLS_2
        repeat(2) { other = other.other() }
        assertSame(LetterPage.SYMBOLS_2, other)
    }

    @Test
    fun `no sequence of switches reaches a page that does not exist`() {
        // The switch key is the only way between pages, so walk it until it
        // repeats and check nothing outside the enum turns up.
        val seen = mutableListOf(LetterPage.LETTERS)
        var page = LetterPage.LETTERS
        repeat(20) {
            page = page.other()
            assertTrue("switched to a page that should not exist: $page", page in LetterPage.entries)
            seen.add(page)
        }
        assertEquals(listOf(LetterPage.LETTERS, LetterPage.SYMBOLS_1, LetterPage.SYMBOLS_2), seen.distinct())
    }
}
