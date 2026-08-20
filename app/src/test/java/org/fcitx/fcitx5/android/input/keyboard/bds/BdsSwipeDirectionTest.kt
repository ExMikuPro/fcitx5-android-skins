/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.bds

import org.fcitx.fcitx5.android.data.theme.bds.BdsDirection
import org.fcitx.fcitx5.android.input.keyboard.SwipeSymbolDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BdsSwipeDirectionTest {
    @Test
    fun downPreferenceTriggersUpperSymbolOnDownwardSwipe() {
        assertEquals(
            BdsDirection.Up,
            resolveBdsSwipeDirection(0f, 40f, 20f, SwipeSymbolDirection.Down)
        )
        assertNull(resolveBdsSwipeDirection(0f, -40f, 20f, SwipeSymbolDirection.Down))
    }

    @Test
    fun upPreferenceTriggersUpperSymbolOnUpwardSwipe() {
        assertEquals(
            BdsDirection.Up,
            resolveBdsSwipeDirection(0f, -40f, 20f, SwipeSymbolDirection.Up)
        )
        assertNull(resolveBdsSwipeDirection(0f, 40f, 20f, SwipeSymbolDirection.Up))
    }

    @Test
    fun disabledPreferenceDoesNotTriggerUpperSymbol() {
        assertNull(resolveBdsSwipeDirection(0f, 40f, 20f, SwipeSymbolDirection.Disabled))
    }

    @Test
    fun horizontalBdsDirectionsRemainAvailable() {
        assertEquals(
            BdsDirection.Right,
            resolveBdsSwipeDirection(40f, 0f, 20f, SwipeSymbolDirection.Disabled)
        )
        assertEquals(
            BdsDirection.Left,
            resolveBdsSwipeDirection(-40f, 0f, 20f, SwipeSymbolDirection.Disabled)
        )
    }
}
