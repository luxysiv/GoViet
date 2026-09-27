package com.goviet.keyboard.ui

/**
 * Which panel the keyboard is showing.
 *
 * This was a `MutableStateFlow<String>` carrying eight spellings, two of which
 * both meant "symbols": [SymbolPage] is a page inside the letter grid,
 * [SymbolPicker] is the full-screen picker with the category tab strip. Nothing
 * stopped a third meaning being added or one being missed, and the two were
 * routinely confused, which is how the picker once rendered the letter grid.
 *
 * A sealed type means the set of panels is one list, and a panel that forgets to
 * configure itself is a compile error rather than a blank screen.
 */
sealed interface PanelState {

    /** Letter grid, letter page. */
    data object Letters : PanelState

    /** Letter grid, first symbol page — what the "123" key on the bar opens. */
    data object SymbolPage : PanelState

    /** Full-screen symbol picker with the category tabs. */
    data object SymbolPicker : PanelState

    data object Emoji : PanelState

    /** Numeric pad, for phone/number/date fields. */
    data object Tpad : PanelState

    data object Clipboard : PanelState

    /** The selection/caret pad. */
    data object EditPad : PanelState

    data object Settings : PanelState

    /** Panels reached from the drawer, which show a back arrow instead of the toolbar toggle. */
    val isDrawerPanel: Boolean
        get() = this == Tpad || this == Clipboard || this == EditPad || this == Settings
}
