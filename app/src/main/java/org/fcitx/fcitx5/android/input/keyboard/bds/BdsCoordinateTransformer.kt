/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.bds

import android.graphics.RectF
import org.fcitx.fcitx5.android.data.theme.bds.BdsPoint
import org.fcitx.fcitx5.android.data.theme.bds.BdsRect

class BdsCoordinateTransformer(
    designWidth: Int,
    designHeight: Int,
    val viewportWidth: Int,
    viewportHeight: Int
) {
    val scaleX = viewportWidth.toFloat() / designWidth.coerceAtLeast(1)
    val scaleY = viewportHeight.toFloat() / designHeight.coerceAtLeast(1)

    fun rect(rect: BdsRect) = RectF(
        rect.x * scaleX,
        rect.y * scaleY,
        (rect.x + rect.width) * scaleX,
        (rect.y + rect.height) * scaleY
    )

    fun x(value: Int) = value * scaleX
    fun y(value: Int) = value * scaleY
    fun point(point: BdsPoint?) = (point?.x ?: 0) * scaleX to (point?.y ?: 0) * scaleY
}
