package com.goviet.keyboard.ui

import android.graphics.RectF

class Key(
    val code: String,
    var label: String,
    var secondaryLabel: String? = null,
    val isFunctional: Boolean = false,
    val isSpecialEnter: Boolean = false,
    val weight: Float = 1.0f,
    var longPressOptions: List<String>? = null,
    val longPressDefaultIndex: Int = 0,
    val isAccent: Boolean = false,
    val isError: Boolean = false,
    val isSelectingStatus: Boolean = false,
    var isCenterPad: Boolean = false,
    val iconId: String? = null
) {
    val rect: RectF = RectF()
    val visualRect: RectF = RectF()
    val shadowRect: RectF = RectF()
    var isPressed: Boolean = false

    /**
     * Standard drop shadow under the key, derived from [visualRect].
     * Call only after visualRect has been laid out (every call site sets
     * visualRect immediately before — Standard/Tpad/Emoji verified).
     */
    fun applyShadow(density: Float) {
        shadowRect.set(
            visualRect.left, visualRect.top + 0.8f * density,
            visualRect.right, visualRect.bottom + 1.2f * density
        )
    }
}

/**
 * Whether (x, y) is on this key's touch target: its rect, or that rect grown
 * to [minTouchPx] around the centre — the same shape [findKeyAt] matches.
 *
 * Move and release handling asks this rather than testing the drawn rect on
 * its own. A press that began in the grown area has to stay valid until the
 * finger lifts, or the key never fires: cancel on the drawn rect instead and
 * the target is dead the moment the finger moves.
 */
internal fun Key.isWithinTarget(x: Float, y: Float, minTouchPx: Float): Boolean {
    val r = rect
    if (r.contains(x, y)) return true
    if (minTouchPx <= 0f) return false
    // What the key has to gain, halved, on each side.
    val growX = (minTouchPx - r.width()).coerceAtLeast(0f) / 2f
    val growY = (minTouchPx - r.height()).coerceAtLeast(0f) / 2f
    return x >= r.left - growX && x <= r.right + growX &&
        y >= r.top - growY && y <= r.bottom + growY
}

/**
 * Shared hit-test, with the touch target grown to [minTouchPx].
 *
 * The drawn key and the target that answers a finger are two different sizes.
 * A row on a 255dp portrait phone is drawn 43.4dp tall and a letter key 35.4dp
 * wide, both below the 48dp a thumb can hit reliably, and making the keys
 * themselves bigger would mean a 278dp panel. So the target is the drawn rect
 * grown to [minTouchPx] around its centre, and the key is drawn exactly as
 * before — the target is invisible, and every side of it counts, so a point
 * exactly 24dp from the centre is inside it.
 *
 * Grown targets overlap, because rows are 7dp apart and a 43.4dp row needs
 * 2.3dp on each side to reach 48dp. Where they overlap, the key whose centre
 * is nearest takes the touch, which is the one the finger was aimed at.
 *
 * A touch that lands inside a drawn key is answered by that key before any of
 * this: growing targets must not change which key a tap on the key itself
 * hits. Returns null when the point is outside every target.
 */
internal fun findKeyAt(keys: List<Key>, x: Float, y: Float, minTouchPx: Float = 0f): Key? {
    for (key in keys) {
        if (key.rect.contains(x, y)) {
            return key
        }
    }
    if (minTouchPx <= 0f) return null

    var best: Key? = null
    var bestDistance = Float.MAX_VALUE
    for (key in keys) {
        if (!key.isWithinTarget(x, y, minTouchPx)) continue
        val r = key.rect
        val dx = x - (r.left + r.right) / 2f
        val dy = y - (r.top + r.bottom) / 2f
        val distance = dx * dx + dy * dy
        if (distance < bestDistance) {
            bestDistance = distance
            best = key
        }
    }
    return best
}
