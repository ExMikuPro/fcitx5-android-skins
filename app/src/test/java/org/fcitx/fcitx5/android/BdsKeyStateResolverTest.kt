/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.theme.bds.BdsAction
import org.fcitx.fcitx5.android.data.theme.bds.BdsDirection
import org.fcitx.fcitx5.android.data.theme.bds.BdsKey
import org.fcitx.fcitx5.android.data.theme.bds.BdsKeyVariant
import org.fcitx.fcitx5.android.data.theme.bds.BdsRect
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAction
import org.fcitx.fcitx5.android.input.keyboard.bds.BdsCapsState
import org.fcitx.fcitx5.android.input.keyboard.bds.BdsKeyStateResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BdsKeyStateResolverTest {
    private val returnKey = BdsKey(
        section = "KEY37",
        viewRect = BdsRect(0, 0, 100, 100),
        touchRect = null,
        backgroundStyle = 1,
        foregroundStyles = listOf(100),
        positionTypes = emptyList(),
        actions = mapOf(BdsDirection.Center to BdsAction("F39")),
        properties = mapOf(
            "STAT_STYLE" to "S11_4|S17_1|S23_2|S27_3|S21_5"
        )
    )
    private val variants = (1..5).map { index ->
        BdsKeyVariant(
            section = "TIP$index",
            backgroundStyle = 200 + index,
            foregroundStyles = listOf(300 + index),
            positionTypes = emptyList(),
            actions = mapOf(BdsDirection.Center to BdsAction("F39")),
            properties = emptyMap()
        )
    }

    @Test
    fun resolvesEditorActionsThroughStatStyle() {
        assertEquals("TIP5", BdsKeyStateResolver.resolve(returnKey, ReturnKeyAction.Search, variants)?.section)
        assertEquals("TIP4", BdsKeyStateResolver.resolve(returnKey, ReturnKeyAction.Done, variants)?.section)
        assertEquals("TIP2", BdsKeyStateResolver.resolve(returnKey, ReturnKeyAction.Go, variants)?.section)
        assertEquals("TIP3", BdsKeyStateResolver.resolve(returnKey, ReturnKeyAction.Send, variants)?.section)
        assertEquals("TIP1", BdsKeyStateResolver.resolve(returnKey, ReturnKeyAction.Next, variants)?.section)
        assertNull(BdsKeyStateResolver.resolve(returnKey, ReturnKeyAction.Enter, variants))
    }

    @Test
    fun resolvesCapsStateThroughStatStyle() {
        val shiftKey = returnKey.copy(
            section = "KEY32",
            properties = mapOf("STAT_STYLE" to "S14_7")
        )
        val capsVariant = BdsKeyVariant(
            section = "TIP7",
            backgroundStyle = 119,
            foregroundStyles = listOf(131),
            positionTypes = listOf(52),
            actions = mapOf(BdsDirection.Center to BdsAction("F11")),
            properties = emptyMap()
        )

        assertEquals(
            "TIP7",
            BdsKeyStateResolver.resolve(
                shiftKey, ReturnKeyAction.Enter, listOf(capsVariant),
                capsState = BdsCapsState.Once
            )?.section
        )
        assertNull(
            BdsKeyStateResolver.resolve(
                shiftKey, ReturnKeyAction.Enter, listOf(capsVariant),
                capsState = BdsCapsState.None
            )
        )
    }

    @Test
    fun resolvesCapsLockThroughShiftLayoutStatStyle() {
        val shiftKey = returnKey.copy(
            section = "KEY32",
            properties = mapOf("STAT_STYLE" to "S2_2")
        )
        val lockVariant = BdsKeyVariant(
            section = "TIP2",
            backgroundStyle = 119,
            foregroundStyles = listOf(131),
            positionTypes = listOf(52),
            actions = mapOf(BdsDirection.Center to BdsAction("F11")),
            properties = emptyMap()
        )

        assertNull(
            BdsKeyStateResolver.resolve(
                shiftKey, ReturnKeyAction.Enter, listOf(lockVariant),
                capsState = BdsCapsState.Once
            )
        )
        assertEquals(
            "TIP2",
            BdsKeyStateResolver.resolve(
                shiftKey, ReturnKeyAction.Enter, listOf(lockVariant),
                capsState = BdsCapsState.Lock
            )?.section
        )
    }
}
