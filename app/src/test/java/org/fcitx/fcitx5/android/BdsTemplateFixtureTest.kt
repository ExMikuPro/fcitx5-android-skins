/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.theme.bds.BdsOrientation
import org.fcitx.fcitx5.android.data.theme.bds.BdsParser
import org.fcitx.fcitx5.android.data.theme.bds.BdsTilParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the checked-in, generator-produced template fixtures against parser regressions. */
class BdsTemplateFixtureTest {
    private fun fixture(name: String): File = listOf(
        File("examples/bds-template/$name"),
        File("../examples/bds-template/$name")
    ).first { File(it, "Info.txt").isFile }

    @Test
    fun parsesMinimalAndShowcaseTemplateFixtures() {
        val minimal = BdsParser.parse(fixture("minimal"), "minimal")
        assertEquals("BDS Minimal Template", minimal.metadata.name)
        assertNotNull(minimal.portraitPinyin26)
        assertNotNull(minimal.portraitNumber26)

        val showcase = BdsParser.parse(fixture("showcase"), "showcase")
        assertEquals("BDS Showcase Template", showcase.metadata.name)
        for (orientation in BdsOrientation.entries) {
            assertNotNull(showcase.layout(orientation, "py_26"))
            assertNotNull(showcase.layout(orientation, "en_26"))
            assertNotNull(showcase.layout(orientation, "num_26"))
            assertNotNull(showcase.layout(orientation, "symbol"))
            assertNotNull(showcase.candidates[orientation])
        }
        val root = fixture("showcase")
        assertEquals(41, BdsTilParser.parse(File(root, "res/logo/pop_menu_icons.til"), 2952, 72)?.tiles?.size)
        assertEquals(8, BdsTilParser.parse(File(root, "res/logo/pop_input_icons.til"), 576, 72)?.tiles?.size)
    }
}
