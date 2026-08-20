/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.bds

import org.fcitx.fcitx5.android.data.theme.bds.BdsAction
import org.fcitx.fcitx5.android.data.theme.bds.BdsLayoutId
import org.fcitx.fcitx5.android.data.theme.bds.BdsOrientation
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BdsActionMappingTest {
    @Test
    fun lowercaseFIsACharacterAction() {
        val mapped = mapBdsAction(BdsAction("f"))

        assertEquals("f", (mapped as KeyAction.FcitxKeyAction).act)
    }

    @Test
    fun uppercaseFNumberIsAFunctionAction() {
        assertTrue(mapBdsAction(BdsAction("F36")) is KeyAction.SymAction)
    }

    @Test
    fun backspaceRepeatIsSelectedByActionSemantics() {
        assertTrue(isBdsBackspaceAction(BdsAction("F36")))
        assertFalse(isBdsBackspaceAction(BdsAction("F39")))
        assertFalse(isBdsBackspaceAction(BdsAction("x")))
    }

    @Test
    fun portraitEnglishLayoutKeepsBdsAnimationsEnabled() {
        assertTrue(bdsAnimationsEnabled(BdsLayoutId(BdsOrientation.Portrait, "en_26")))
        assertFalse(bdsAnimationsEnabled(BdsLayoutId(BdsOrientation.Landscape, "en_26")))
    }

    @Test
    fun bdsReturnActionSwitchesBackToFcitxTextLayout() {
        assertTrue(mapBdsAction(BdsAction("F4")) is KeyAction.LayoutSwitchAction)
    }

    @Test
    fun englishModeFunctionIsNotMisinterpretedAsComma() {
        // en_26.ini renders F25 as the BDS "abc" mode key. Fcitx does not
        // expose Baidu's corresponding smart-English mode, so fall back
        // gracefully instead of committing punctuation with a mismatched icon.
        assertNull(mapBdsAction(BdsAction("F25")))
    }

    @Test
    fun uppercaseFWithoutNumberRemainsText() {
        val mapped = mapBdsAction(BdsAction("F"))

        assertEquals("F", (mapped as KeyAction.FcitxKeyAction).act)
    }

    @Test
    fun characterPopupUsesCurrentCapsState() {
        val action = BdsAction("e")

        assertEquals("e", bdsPopupPresetLabel(action, false))
        assertEquals("E", bdsPopupPresetLabel(action, true))
    }

    @Test
    fun functionAndUnsupportedCharactersHaveNoPopup() {
        assertNull(bdsPopupPresetLabel(BdsAction("F11"), false))
        assertNull(bdsPopupPresetLabel(BdsAction("。"), false))
    }
}
