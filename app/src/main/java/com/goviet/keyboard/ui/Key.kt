package com.goviet.keyboard.ui

import android.graphics.RectF

class Key(
    val code: String,
    var label: String,
    val secondaryLabel: String? = null,
    val isFunctional: Boolean = false,
    val isSpecialEnter: Boolean = false,
    val weight: Float = 1.0f,
    val longPressOptions: List<String>? = null,
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
}
