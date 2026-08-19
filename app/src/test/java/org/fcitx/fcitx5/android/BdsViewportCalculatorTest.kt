/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.keyboard.bds.BdsViewportCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

class BdsViewportCalculatorTest {
    @Test
    fun `portrait panel applies legacy Baidu vertical viewport scale`() {
        assertEquals(688, BdsViewportCalculator.portraitPanelHeight(1080, 655, 1080))
        assertEquals(306, BdsViewportCalculator.portraitPanelHeight(1080, 655, 480))
    }

    @Test
    fun `invalid panel dimensions return zero`() {
        assertEquals(0, BdsViewportCalculator.portraitPanelHeight(0, 655, 1080))
        assertEquals(0, BdsViewportCalculator.portraitPanelHeight(1080, -1, 1080))
    }
}
