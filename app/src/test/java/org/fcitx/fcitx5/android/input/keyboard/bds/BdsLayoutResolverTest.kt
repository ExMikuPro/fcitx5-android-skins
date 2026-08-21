/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.bds

import org.fcitx.fcitx5.android.data.theme.bds.BdsLayout
import org.fcitx.fcitx5.android.data.theme.bds.BdsLayoutId
import org.fcitx.fcitx5.android.data.theme.bds.BdsMetadata
import org.fcitx.fcitx5.android.data.theme.bds.BdsOrientation
import org.fcitx.fcitx5.android.data.theme.bds.BdsResources
import org.fcitx.fcitx5.android.data.theme.bds.BdsSkin
import org.junit.Assert.assertEquals
import org.junit.Test

class BdsLayoutResolverTest {
    @Test
    fun portraitOnlySkinSafelyFallsBackWhenLandscapeIsRequested() {
        val portrait = BdsLayout(BdsLayoutId(BdsOrientation.Portrait, "py_26"), 480, 385,
            null, emptyList(), emptyList(), emptyList(), emptyMap())
        val skin = BdsSkin("test", "/test", BdsMetadata("test", null, null, null, null, "test", "/test", emptyMap()),
            mapOf(portrait.id to portrait), emptyMap(), emptyMap(),
            mapOf(BdsOrientation.Portrait to BdsResources(480, 385, null, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())), emptyList())

        assertEquals(portrait, BdsLayoutResolver.resolve(skin, BdsOrientation.Landscape, BdsLayoutResolver.Purpose.Text26))
    }
}
