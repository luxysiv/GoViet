package com.goviet.keyboard.ui

import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper

/**
 * One key or cell of a custom-drawn panel, as something a screen reader can be
 * sent to.
 *
 * [bounds] are the drawn key's own rectangle, not the grown touch target: what
 * a screen reader draws around a node is where the key looks, and a target
 * that overlaps its neighbour would put two overlapping rectangles on screen.
 */
class KeyGridCell(
    val bounds: RectF,
    val description: CharSequence,
    val onActivate: () -> Unit
)

/**
 * Walks the keys of a panel that draws itself, so a screen reader has keys to
 * land on.
 *
 * Every panel here is a [View] with a [android.graphics.Canvas] in onDraw and
 * nothing else: no child views, no nodes. A screen reader sees one rectangle
 * per panel, so the emoji, the symbols and the numeric pad are all unreachable
 * and the only way to press one is to guess where it is by touch. This turns
 * the keys a panel already knows how to draw into nodes — one per key, labelled
 * with what the key shows, activating what the key does.
 *
 * The cell list is asked for on every call rather than held here, because the
 * panels are not static: scrolling moves a symbol cell, switching tabs replaces
 * it, and a cell list that went stale would send a screen reader to a key that
 * is no longer there. A node is its index in that list, so the index only has
 * to hold still while a screen reader is walking one panel, and a panel calls
 * [invalidateRoot] whenever it changes what it draws.
 *
 * The panel forwards hover events to [dispatchHoverEvent] and calls [attach]:
 *
 * ```
 * private val accessibility = KeyGridTouchHelper(this) { accessibilityCells() }
 *
 * init { accessibility.attach() }
 *
 * override fun dispatchHoverEvent(event: MotionEvent): Boolean =
 *     accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
 * ```
 */
class KeyGridTouchHelper(
    private val host: View,
    private val cellsProvider: () -> List<KeyGridCell>
) : ExploreByTouchHelper(host) {

    /**
     * Makes this the panel's accessibility delegate.
     *
     * Not done in the constructor: a panel that builds the helper as a field
     * property assigns it before the panel's own init block has run, and
     * [ViewCompat.setAccessibilityDelegate] reads the view.
     */
    fun attach() {
        ViewCompat.setAccessibilityDelegate(host, this)
    }

    /** The cells as they are right now. */
    fun cells(): List<KeyGridCell> = cellsProvider()

    /**
     * Which cell a touch at (x, y) is on, or [NO_CELL].
     *
     * The drawn rectangle and nothing else: a screen reader sends a node a
     * click, and this is the same question a finger asks, so the two cannot
     * answer differently about the same point.
     */
    fun indexAt(x: Float, y: Float): Int {
        val list = cells()
        for (index in list.indices) {
            if (list[index].bounds.contains(x, y)) return index
        }
        return NO_CELL
    }

    override fun getVirtualViewAt(x: Float, y: Float): Int = indexAt(x, y)

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        val list = cells()
        for (index in list.indices) {
            virtualViewIds.add(index)
        }
    }

    // A node of a virtual child is framed in its host's coordinates, and
    // setBoundsInParent is the only method that says so; core deprecated it
    // without naming a replacement, and bounds in screen or in window would be
    // the wrong space for a key drawn inside the panel.
    @Suppress("DEPRECATION")
    override fun onPopulateNodeForVirtualView(
        virtualViewId: Int,
        node: AccessibilityNodeInfoCompat
    ) {
        val cell = cellAt(virtualViewId) ?: return
        node.contentDescription = cell.description
        // The keys are not buttons in a view hierarchy and never will be, but
        // a button is what this is — something that does one thing when
        // pressed — and what a screen reader offers on the node.
        node.className = Button::class.java.name
        node.isClickable = true
        node.addAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.setBoundsInParent(boundsOf(cell))
    }

    override fun onPopulateNodeForHost(node: AccessibilityNodeInfoCompat) {
        // The panel's own node says nothing about its keys; the keys are nodes
        // of their own, and a screen reader walks into them from here.
        node.className = View::class.java.name
    }

    override fun onPerformActionForVirtualView(
        virtualViewId: Int,
        action: Int,
        arguments: Bundle?
    ): Boolean {
        if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
        val cell = cellAt(virtualViewId) ?: return false
        cell.onActivate()
        // The key is pressed now, and the panel draws that: a screen reader
        // left holding a node describing the key as it was is describing
        // something that is no longer on screen.
        invalidateRoot()
        host.invalidate()
        return true
    }

    private fun cellAt(virtualViewId: Int): KeyGridCell? {
        if (virtualViewId < 0) return null
        return cells().getOrNull(virtualViewId)
    }

    private fun boundsOf(cell: KeyGridCell): Rect = Rect(
        cell.bounds.left.toInt(),
        cell.bounds.top.toInt(),
        cell.bounds.right.toInt(),
        cell.bounds.bottom.toInt()
    )

    /**
     * A touch that is on no key.
     *
     * The framework's own "no virtual view" value, and the one it reads as the
     * host panel rather than a key.
     */
    companion object {
        const val NO_CELL = -1
    }
}
