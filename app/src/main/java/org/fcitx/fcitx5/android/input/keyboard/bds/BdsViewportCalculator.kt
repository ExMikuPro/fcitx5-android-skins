/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.bds

import org.fcitx.fcitx5.android.data.theme.bds.BdsOrientation
import kotlin.math.roundToInt

/**
 * Converts a portrait BDS panel's design size into the viewport used by the
 * legacy Baidu Android renderer.
 *
 * The renderer does not preserve the panel's aspect ratio. On a viewport whose
 * width equals the skin's FOR/PANEL width, the portrait panel is 105% of its
 * declared design height. This was verified against corresponding horizontal
 * edges in the original renderer: the error decreases linearly from the first
 * row to zero at the bottom edge. Keeping this rule here prevents renderer code
 * and individual keys from acquiring skin-specific offsets.
 */
object BdsViewportCalculator {
    private const val PORTRAIT_PANEL_HEIGHT_NUMERATOR = 105
    private const val PORTRAIT_PANEL_HEIGHT_DENOMINATOR = 100

    fun portraitPanelHeight(designWidth: Int, designHeight: Int, viewportWidth: Int): Int {
        if (designWidth <= 0 || designHeight <= 0 || viewportWidth <= 0) return 0
        return (designHeight.toDouble() * viewportWidth * PORTRAIT_PANEL_HEIGHT_NUMERATOR /
            designWidth / PORTRAIT_PANEL_HEIGHT_DENOMINATOR).roundToInt()
    }

    fun panelHeight(
        designWidth: Int,
        designHeight: Int,
        viewportWidth: Int,
        orientation: BdsOrientation
    ): Int {
        if (orientation == BdsOrientation.Portrait) {
            return portraitPanelHeight(designWidth, designHeight, viewportWidth)
        }
        if (designWidth <= 0 || designHeight <= 0 || viewportWidth <= 0) return 0
        return (designHeight.toDouble() * viewportWidth / designWidth).roundToInt()
    }
}
